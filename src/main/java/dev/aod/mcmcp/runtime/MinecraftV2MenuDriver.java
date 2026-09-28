package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.navigation.NavCell;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.inventory.ContainerInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Bounded menu work uses the same ordinary block use and causal screen ownership as storage. */
final class MinecraftV2MenuDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final ScreenOwnershipSignals screens = ScreenOwnershipSignals.global();
    private final UUID owner = UUID.randomUUID();
    private final V2MenuArguments request;
    private final MinecraftV2BlockInteractDriver opener;
    private boolean started;
    private boolean expectedOpen;
    private Object player;
    private Object level;
    private Object screen;
    private long openedTick;
    private long clickedTick;
    private int completed;
    private ContainerSyncSignals.ContainerSnapshot beforeClick;
    private ContainerSyncSignals.ContainerSnapshot latest;
    private String failure;

    MinecraftV2MenuDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            AgentObservations observations, ClientReconciliationSignals reconciliation,
            V2MenuArguments request, DoubleSupplier remainingDistance) {
        this.minecraft = minecraft;
        this.sessions = sessions;
        this.request = request;
        opener = new MinecraftV2BlockInteractDriver(minecraft, sessions, observations,
                reconciliation, ClientPredictionSignals.global(), remainingDistance, this);
    }

    public void begin(long tick, BooleanSupplier allowed) {
        player = minecraft.player;
        level = minecraft.level;
    }

    public V2OperationJobExecution.Step tick(long tick, BooleanSupplier allowed) {
        if (!started) {
            var begin = opener.begin(request.block().region().cells().getFirst(), request.block(), allowed);
            if (begin == V2BlockJobExecution.BeginResult.WAITING) return V2OperationJobExecution.Step.RUNNING;
            if (begin != V2BlockJobExecution.BeginResult.STARTED) return fail("menu_target_unavailable");
            started = true;
        }
        return switch (opener.tick(tick, allowed)) {
            case RUNNING -> V2OperationJobExecution.Step.RUNNING;
            case CONFIRMED -> V2OperationJobExecution.Step.CONFIRMED;
            case FAILED, SKIPPED -> fail("menu_not_confirmed");
        };
    }

    boolean beforeUse(NavCell target, BlockStateFingerprint before, long tick) {
        if (expectedOpen) return false;
        var token = new ExpectedOpenToken(sessions.get().worldSessionId(), owner,
                target.toString(), before.toString(), request.menuType(), screens.currentTick() + 80);
        expectedOpen = screens.beginExpectedOpen(token);
        openedTick = tick;
        return expectedOpen;
    }

    V2BlockJobExecution.StepResult tickMenu(long tick, BooleanSupplier allowed) {
        var state = screens.snapshot();
        if (state.phase() == ScreenOwnershipSignals.Phase.FAILED) return menuFail("menu_ownership_failed");
        var owned = screens.ownedSession().filter(value -> value.token().routineId().equals(owner));
        if (owned.isEmpty()) return tick - openedTick <= 80 ? V2BlockJobExecution.StepResult.RUNNING
                : menuFail("menu_open_timeout");
        var content = owned.orElseThrow().serverSnapshot();
        var live = minecraft.player.containerMenu;
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> container)
                || container.getMenu() != live || live.containerId != content.containerId()
                || !request.menuType().equals(ScreenOwnershipSignals.registeredMenuTypeId(live).orElse(null))
                || live.slots.size() != content.slots().size() || live.slots.size() > 256
                || !live.getCarried().isEmpty() || !content.carried().empty()
                || screen != null && screen != container) return menuFail("menu_context_changed");
        screen = container;
        latest = content;
        if (beforeClick != null) {
            if (state.lastServerCursorProven() && state.lastServerCursorEmpty()
                    && content.packetLedgerRevision() > beforeClick.packetLedgerRevision()
                    && wholeStackMoved(beforeClick.slots(), content.slots(), request.clicks().get(completed).slot())) {
                beforeClick = null;
                completed++;
            } else return tick - clickedTick <= 60 ? V2BlockJobExecution.StepResult.RUNNING
                    : menuFail("menu_click_not_confirmed");
        }
        if (completed == request.clicks().size()) return V2BlockJobExecution.StepResult.CONFIRMED;
        var click = request.clicks().get(completed);
        // Vanilla shift-click does not touch the cursor. Custom menus need a provider with
        // their own synchronization/side-effect contract before they can accept clicks.
        if (!supportsStorageClicks(request.menuType())
                || !live.getClass().getName().startsWith("net.minecraft.world.inventory.")) {
            return menuFail("unsupported_menu_click");
        }
        if (click.slot() >= live.slots.size()) return menuFail("menu_slot_outside_screen");
        if (live.getSlot(click.slot()).getClass() != net.minecraft.world.inventory.Slot.class) {
            return menuFail("unsupported_menu_slot");
        }
        var source = content.slots().get(click.slot());
        if (!source.itemId().equals(click.item()) || source.count() != click.count()
                || !source.equals(ContainerSyncSignals.StackFingerprint.fromServerPacket(live.getSlot(click.slot()).getItem()))
                || !live.getSlot(click.slot()).mayPickup(minecraft.player)
                || !state.lastServerCursorProven() || !state.lastServerCursorEmpty()
                || !allowed.getAsBoolean()) return menuFail("menu_slot_condition_changed");
        // Capture intent before crossing the network boundary. An uncertain click is never repeated.
        beforeClick = content;
        clickedTick = tick;
        var connection = minecraft.getConnection();
        connection.send(new ServerboundContainerClickPacket(live.containerId, live.getStateId(),
                (short) click.slot(), (byte) 0, ContainerInput.QUICK_MOVE,
                new Int2ObjectOpenHashMap<>(), HashedStack.create(live.getCarried(), connection.decoratedHashOpsGenenerator())));
        return V2BlockJobExecution.StepResult.RUNNING;
    }

    static boolean supportsStorageClicks(String type) {
        return type.matches("minecraft:generic_9x[1-6]") || type.equals("minecraft:generic_3x3")
                || type.equals("minecraft:hopper") || type.equals("minecraft:shulker_box");
    }

    /** All units moved from one slot into unchanged-component destination stacks, without other changes. */
    static boolean wholeStackMoved(List<ContainerSyncSignals.StackFingerprint> before,
            List<ContainerSyncSignals.StackFingerprint> after, int source) {
        if (before.size() != after.size() || source < 0 || source >= before.size()) return false;
        var moved = before.get(source);
        if (moved.empty() || !after.get(source).empty()) return false;
        int gained = 0;
        for (int slot = 0; slot < before.size(); slot++) {
            if (slot == source || before.get(slot).equals(after.get(slot))) continue;
            var old = before.get(slot);
            var next = after.get(slot);
            if (!next.itemId().equals(moved.itemId()) || next.itemAndComponentsHash() != moved.itemAndComponentsHash()
                    || !old.empty() && (!old.itemId().equals(moved.itemId()) || old.itemAndComponentsHash() != moved.itemAndComponentsHash())
                    || next.count() < old.count()) return false;
            gained += next.count() - old.count();
        }
        return gained == moved.count();
    }

    void releaseMenu(ClientPredictionSignals.PredictionAttempt prediction) {
        if (!expectedOpen) return;
        if (minecraft.player != player || minecraft.level != level) {
            if (!screens.releaseRoutineOnIdentityLoss(owner)) throw new IllegalStateException("menu identity release pending");
            expectedOpen = false;
            return;
        }
        var barrier = prediction == null ? ScreenOwnershipSignals.CausalBarrierStatus.NOT_REQUIRED
                : switch (prediction.acknowledgement().status()) {
                    case ACKNOWLEDGED -> ScreenOwnershipSignals.CausalBarrierStatus.ACKNOWLEDGED;
                    case IDENTITY_RELEASED -> ScreenOwnershipSignals.CausalBarrierStatus.IDENTITY_RELEASED;
                    case NO_PREDICTION, WAITING_ACK -> ScreenOwnershipSignals.CausalBarrierStatus.WAITING_ACK;
                    case INCOMPATIBLE, CLOSED -> ScreenOwnershipSignals.CausalBarrierStatus.INCOMPATIBLE;
                };
        var decision = screens.cancelRoutineAfterPredictedUse(owner, barrier);
        if (decision.closeMenuBestEffort() && decision.serverCursorEmpty()
                && minecraft.player.containerMenu.containerId == decision.containerId()
                && decision.menuTypeId().equals(ScreenOwnershipSignals.registeredMenuTypeId(minecraft.player.containerMenu).orElse(null))) {
            if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> container)) {
                throw new IllegalStateException("owned screen changed before release");
            }
            container.onClose();
            screens.onScreenClosing(container).ifPresent(reason -> { throw new IllegalStateException(reason); });
        }
        if (screens.snapshot().phase() != ScreenOwnershipSignals.Phase.IDLE) throw new IllegalStateException("menu release pending");
        expectedOpen = false;
    }

    public boolean allowsScreenChange() { return expectedOpen; }
    public void close() { opener.close(); }
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("target", "menu");
        result.put("menu_type", request.menuType());
        result.put("confirmed_clicks", completed);
        result.put("unconfirmed_click", beforeClick != null);
        if (latest != null) result.put("slots", java.util.stream.IntStream.range(0, latest.slots().size())
                .mapToObj(slot -> Map.<String, Object>of("slot", slot, "item", latest.slots().get(slot).itemId(),
                        "count", latest.slots().get(slot).count())).toList());
        if (failure != null) result.put("failure", failure);
        return result;
    }
    private V2OperationJobExecution.Step fail(String reason) {
        if (failure == null) failure = reason;
        return V2OperationJobExecution.Step.FAILED;
    }
    private V2BlockJobExecution.StepResult menuFail(String reason) {
        failure = reason;
        return V2BlockJobExecution.StepResult.FAILED;
    }
}
