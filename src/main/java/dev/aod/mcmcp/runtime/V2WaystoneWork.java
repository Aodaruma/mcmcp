package dev.aod.mcmcp.runtime;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Selection completion means one native request was sent; the caller must verify the subsequent transfer. */
final class V2WaystoneWork implements MinecraftV2MenuDriver.Work {
    private final Minecraft minecraft;
    private final UUID session;
    private final V2WaystoneArguments request;
    private final V2WaystoneReferences references;
    private final Map<String, Object> result = new LinkedHashMap<>();
    private boolean finished;

    V2WaystoneWork(Minecraft minecraft, UUID session, V2WaystoneArguments request, V2WaystoneReferences references) {
        this.minecraft = minecraft; this.session = session; this.request = request; this.references = references;
        result.put("target", "item"); result.put("item", V2WaystoneArguments.ITEM);
        result.put("operation", request.inspect() ? "inspect_destinations" : "select_destination");
        result.put("selection_dispatched", false); result.put("effect_confirmed", false);
    }

    @Override public V2BlockJobExecution.StepResult tick(AbstractContainerMenu menu,
            ContainerSyncSignals.ContainerSnapshot snapshot, long tick, BooleanSupplier allowed) {
        if (finished) return V2BlockJobExecution.StepResult.CONFIRMED;
        var player = minecraft.player;
        var held = ContainerSyncSignals.StackFingerprint.fromServerPacket(player.getMainHandItem());
        if (!V2WaystoneArguments.ITEM.equals(held.itemId()) || !snapshot.carried().empty()) return fail("waystone_item_changed");
        var pos = player.blockPosition();
        var scope = new V2WaystoneReferences.Scope(session, player.level().dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), held);
        final java.util.List<V2WaystoneReferences.Destination> destinations;
        try { destinations = WaystoneMenuSupport.read(minecraft, menu); }
        catch (IllegalArgumentException unavailable) { return fail("waystone_menu_unsupported"); }
        try {
            if (request.inspect()) {
                result.put("destinations", destinations.stream().map(destination -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("destination_ref", references.register(scope, tick, destination));
                    row.put("name", destination.name()); row.put("dimension", destination.dimension());
                    row.put("xp_levels", destination.levels()); row.put("xp_points", destination.points());
                    row.put("selectable", destination.selectable()); row.put("requirements", destination.requirements());
                    return Map.copyOf(row);
                }).toList());
                result.put("complete", true);
            } else {
                var old = references.resolve(request.destinationRef(), scope, tick);
                var current = destinations.stream().filter(value -> value.id().equals(old.destination().id())).findFirst().orElse(null);
                if (!allowed.getAsBoolean()) return fail("waystone_dispatch_denied");
                var destination = references.consume(request.destinationRef(), scope, tick, current, request.maxLevels(), request.maxPoints());
                result.put("expected_destination", Map.of("name", destination.name(), "dimension", destination.dimension()));
                result.put("selection_dispatched", true); // An uncertain native send is never repeated.
                WaystoneMenuSupport.select(minecraft, destination.id());
                result.put("verification", "pending_transfer; check player state before further input or another selection");
            }
            finished = true;
            return V2BlockJobExecution.StepResult.CONFIRMED;
        } catch (IllegalArgumentException unavailable) {
            return fail("waystone_destination_or_cost_changed");
        }
    }

    private V2BlockJobExecution.StepResult fail(String code) {
        result.put("failure", code);
        return V2BlockJobExecution.StepResult.FAILED;
    }
    @Override public Map<String, Object> result() { return Map.copyOf(result); }
}
