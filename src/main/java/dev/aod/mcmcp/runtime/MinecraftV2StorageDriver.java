package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import dev.aod.mcmcp.agent.action.KnownContainerAttempt;
import dev.aod.mcmcp.routine.PhaseFivePort;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Resolves the carried item once, then delegates all menu work to the shared transfer port. */
final class MinecraftV2StorageDriver implements V2OperationJobExecution.Driver {
    private final Minecraft minecraft;
    private final Supplier<WorldSessionTracker.Snapshot> sessions;
    private final KnownStorageRefs storages;
    private final PhaseFivePort port;
    private final V2InventoryStorageArguments request;
    private final List<Map<String, Object>> effects = new ArrayList<>();
    private KnownContainerAttempt attempt;
    private String reference;
    private UUID sessionId;
    private String storageItem;
    private List<Map<String, Object>> contents;
    private int confirmedCount;
    private boolean uncertain;
    private String failure;

    MinecraftV2StorageDriver(Minecraft minecraft, Supplier<WorldSessionTracker.Snapshot> sessions,
            KnownStorageRefs storages, PhaseFivePort port, V2InventoryStorageArguments request) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.sessions = Objects.requireNonNull(sessions);
        this.storages = Objects.requireNonNull(storages);
        this.port = Objects.requireNonNull(port);
        this.request = Objects.requireNonNull(request);
    }

    @Override
    public void begin(long clientTick, BooleanSupplier outputAllowed) {
        if (!outputAllowed.getAsBoolean()) { failure = "storage_dispatch_denied"; return; }
        var target = storages.atSlot(minecraft, request.storageSlot()).orElse(null);
        if (target == null || !target.stillPresent(minecraft)) {
            failure = "storage_provider_or_target_unavailable";
            return;
        }
        storageItem = BuiltInRegistries.ITEM.getKey(target.item().getItem()).toString();
        if (request.storageItem() != null && !request.storageItem().equals(storageItem)) {
            failure = "storage_item_condition_changed";
            return;
        }
        var session = sessions.get();
        sessionId = session.worldSessionId();
        long deadline = Math.addExact(clientTick, request.maxTicks());
        reference = storages.issue(sessionId, minecraft.player, target, deadline);
        attempt = new KnownContainerAttempt(port, request.operation(reference,
                ActionPlanning.playerCell(minecraft.player, session.dimension())), clientTick, deadline);
    }

    @Override
    public V2OperationJobExecution.Step tick(long clientTick, BooleanSupplier outputAllowed) {
        if (failure != null) return V2OperationJobExecution.Step.FAILED;
        if (!outputAllowed.getAsBoolean() || !sessionId.equals(sessions.get().worldSessionId())) {
            return fail("storage_context_changed");
        }
        var outcome = attempt.tick(clientTick);
        captureEffects(outcome.effects());
        if (outcome.status() == KnownContainerAttempt.Status.FAILED) return fail(outcome.evidence());
        if (outcome.status() == KnownContainerAttempt.Status.SUCCEEDED) {
            if (request.inspect()) {
                var inspection = storages.inspection(sessionId);
                if (inspection == null || !reference.equals(inspection.get("operation_ref"))) {
                    return fail("storage_inspection_not_confirmed");
                }
                contents = filteredContents(inspection, request.item());
            } else if (confirmedCount != request.count() || uncertain) {
                return fail("storage_quantity_not_confirmed");
            }
            return V2OperationJobExecution.Step.CONFIRMED;
        }
        return V2OperationJobExecution.Step.RUNNING;
    }

    static List<Map<String, Object>> filteredContents(Map<String, Object> inspection, String item) {
        if (!(inspection.get("contents") instanceof List<?> entries)) {
            throw new IllegalStateException("storage inspection contents missing");
        }
        var result = new ArrayList<Map<String, Object>>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> value) || !(value.get("item") instanceof String id)
                    || !(value.get("slot") instanceof Integer slot) || slot < 0
                    || !(value.get("count") instanceof Integer count) || count < 1) {
                throw new IllegalStateException("storage inspection contents invalid");
            }
            if (item == null || item.equals(id)) result.add(Map.of("slot", slot, "item", id, "count", count));
        }
        return List.copyOf(result);
    }

    @Override
    public boolean allowsScreenChange() { return attempt != null; }

    @Override
    public Map<String, Object> result() {
        var result = new LinkedHashMap<String, Object>();
        result.put("operation", request.inspect() ? "inspect" : "transfer");
        result.put("target", "storage");
        result.put("storage_slot", request.storageSlot());
        if (storageItem != null) result.put("storage_item", storageItem);
        if (request.item() != null) result.put("item", request.item());
        if (request.inspect()) {
            result.put("complete", contents != null);
            if (contents != null) result.put("slots", contents);
        } else {
            result.put("direction", request.store() ? "store" : "take");
            result.put("requested_count", request.count());
            result.put("confirmed_count", confirmedCount);
            result.put("unconfirmed", uncertain);
            if (!effects.isEmpty()) result.put("effects", List.copyOf(effects));
        }
        if (failure != null) result.put("failure", failure);
        return result;
    }

    private void captureEffects(List<KnownContainerAttempt.EffectDelta> deltas) {
        for (var delta : deltas) {
            boolean confirmed = delta.verification() == AgentActionStore.Verification.CONFIRMED;
            if (confirmed) confirmedCount = Math.addExact(confirmedCount,
                    ((Number) delta.observedAfter().get("transferred")).intValue());
            else uncertain = true;
            effects.add(Map.of("verification", confirmed ? "confirmed" : "unknown",
                    "before", delta.observedBefore(), "after", delta.observedAfter()));
        }
    }

    private V2OperationJobExecution.Step fail(String reason) {
        failure = reason;
        return V2OperationJobExecution.Step.FAILED;
    }

    @Override
    public void close() {
        if (attempt == null) return;
        try { attempt.close(); }
        finally { captureEffects(attempt.drainEffectDeltas()); }
    }
}
