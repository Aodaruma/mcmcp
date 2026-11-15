package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.client.McmcpClientConfig;
import net.minecraft.client.Minecraft;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Selects only the configured destination from a freshly received native Waystones menu. */
final class MinecraftEmergencyWaystone implements AutoCloseable {
    enum State { WAITING, TRANSFERRED, FAILED }
    private final Minecraft minecraft;
    private final MinecraftV2ItemUseDriver use;
    private final UUID destination;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private V2WaystoneReferences.Destination selected;
    private long started = -1;
    private boolean sent, closed;

    MinecraftEmergencyWaystone(Minecraft minecraft, UUID session, Supplier<WorldSessionTracker.Snapshot> sessions) {
        this.minecraft = minecraft;
        destination = UUID.fromString(McmcpClientConfig.recoveryWaystoneDestination());
        var menu = new MinecraftV2MenuDriver(minecraft, sessions, WaystoneMenuSupport.MENU);
        menu.withWork(new MinecraftV2MenuDriver.Work() {
            public V2BlockJobExecution.StepResult tick(net.minecraft.world.inventory.AbstractContainerMenu current,
                    ContainerSyncSignals.ContainerSnapshot snapshot, long tick, BooleanSupplier allowed) {
                if (sent) return V2BlockJobExecution.StepResult.CONFIRMED;
                var row = WaystoneMenuSupport.read(minecraft, current).stream()
                        .filter(entry -> entry.id().equals(destination)).findFirst().orElse(null);
                if (row == null || !row.selectable() || row.levels() > McmcpClientConfig.recoveryWaystoneMaxLevels()
                        || row.points() > McmcpClientConfig.recoveryWaystoneMaxPoints() || !allowed.getAsBoolean()) {
                    result.put("failure", "configured_waystone_unavailable_or_over_budget");
                    return V2BlockJobExecution.StepResult.FAILED;
                }
                selected = row;
                sent = true;
                result.put("selection_dispatched", true);
                result.put("destination", destination.toString());
                WaystoneMenuSupport.select(minecraft, destination);
                return V2BlockJobExecution.StepResult.CONFIRMED;
            }
            public Map<String, Object> result() { return Map.copyOf(result); }
        });
        use = new MinecraftV2ItemUseDriver(minecraft, session,
                new V2ItemUseArguments(V2WaystoneArguments.ITEM, null, 80, 400)).withMenu(menu);
    }
    State tick(long tick, BooleanSupplier allowed) {
        if (!WaystoneMenuSupport.available() || tick - started > 400 && started >= 0) return State.FAILED;
        if (selected != null && minecraft.player != null && minecraft.level != null
                && minecraft.level.dimension().identifier().toString().equals(selected.dimension())
                && minecraft.player.position().distanceTo(new net.minecraft.world.phys.Vec3(
                        selected.x() + 0.5, selected.y(), selected.z() + 0.5)) < 4) {
            result.put("transfer_confirmed", true); return State.TRANSFERRED;
        }
        if (started < 0) { started = tick; use.begin(tick, allowed); }
        if (!closed) {
            var step = use.tick(tick, allowed);
            if (step == V2OperationJobExecution.Step.FAILED) return State.FAILED;
            if (step == V2OperationJobExecution.Step.CONFIRMED) { use.close(); closed = true; }
        }
        return State.WAITING;
    }
    Map<String, Object> result() { return Map.copyOf(result); }
    @Override public void close() { if (!closed) { use.close(); closed = true; } }
}
