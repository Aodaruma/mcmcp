package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.mcp.McpRuntimePort;
import dev.aodaruma.craftagent.mcp.RuntimeCallContext;
import dev.aodaruma.craftagent.observation.BlockPlanComparator;
import dev.aodaruma.craftagent.observation.MinecraftObservationService;
import dev.aodaruma.craftagent.observation.WorldMemory;
import dev.aodaruma.craftagent.safety.InputReleaseController;
import dev.aodaruma.craftagent.safety.LocalArmingState;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/** Phase 1 client runtime and the sole implementation of the MCP-to-Minecraft boundary. */
public final class CraftAgentRuntime implements McpRuntimePort {
    private static final String MCP_PROTOCOL_VERSION = "2025-11-25";
    /** Expanded only when a phase has passed its gate; Phase 1 is read/stop-only. */
    private static final Set<String> AVAILABLE_CAPABILITIES = Set.of();

    private final String modVersion;
    private final String neoForgeVersion;
    private final WorldSessionTracker sessions = new WorldSessionTracker();
    private final WorldMemory memory = new WorldMemory();
    private final MinecraftObservationService observations = new MinecraftObservationService(memory);
    private final BlockPlanComparator blockPlans = new BlockPlanComparator(observations, memory);
    private final LocalArmingState arming = new LocalArmingState();
    private final InputReleaseController inputRelease = new InputReleaseController();
    private final ClientCommandInbox inbox = new ClientCommandInbox(inputRelease, arming);

    private volatile WorldSessionTracker.Snapshot publishedSession = sessions.snapshot();
    private volatile boolean paused;
    private volatile boolean shutdown;

    public CraftAgentRuntime(String modVersion, String neoForgeVersion) {
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
        this.neoForgeVersion = Objects.requireNonNull(neoForgeVersion, "neoForgeVersion");
    }

    public void onResourcesReady() {
        sessions.resourcesReady();
        publishSession();
    }

    public void onLoggingIn(Minecraft minecraft) {
        assertClientThread(minecraft);
        inputRelease.releaseAll(minecraft);
        sessions.beginConnection();
        arming.lock("world_join");
        publishSession();
    }

    public void onLevelUnload(Minecraft minecraft) {
        assertClientThread(minecraft);
        inputRelease.releaseAll(minecraft);
        sessions.suspendWorld();
        arming.lock("level_or_dimension_change");
        publishSession();
    }

    public void onLoggingOut(Minecraft minecraft) {
        assertClientThread(minecraft);
        inputRelease.releaseAll(minecraft);
        sessions.invalidate();
        memory.detachSession();
        arming.lock("disconnect");
        publishSession();
    }

    public void onPlayerClone(Minecraft minecraft) {
        assertClientThread(minecraft);
        inbox.requestEmergencyStop("player_respawn");
    }

    public void onPauseChanged(boolean paused) {
        this.paused = paused;
    }

    public void onPreTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        synchronizeWorld(minecraft);
        sessions.tick();
        publishSession();
        inbox.drainEmergencyStopPreTick(minecraft, publishedSession);
        publishSession();
    }

    public void onPostTick(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        inbox.drainReadsPostTick(sessions.snapshot());
        publishSession();
    }

    public void toggleLocalArming(Minecraft minecraft) {
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        if (!session.worldReady()) {
            overlay(minecraft, "CraftAgent: ワールド準備前のため解除できません");
            return;
        }
        var current = arming.snapshot(session.worldSessionId(), System.nanoTime());
        if (!current.locked()) {
            arming.lock("local_key");
            inputRelease.releaseAll(minecraft);
            overlay(minecraft, "CraftAgent: ロックしました");
        } else {
            arming.arm(session.worldSessionId(), AVAILABLE_CAPABILITIES,
                    LocalArmingState.DEFAULT_ARM_DURATION, System.nanoTime());
            overlay(minecraft, "CraftAgent: 15分間ローカル解除しました");
        }
    }

    public void emergencyStopFromLocalKey(Minecraft minecraft) {
        assertClientThread(minecraft);
        inbox.requestEmergencyStop("local_emergency_key");
        // The local key is already on the client thread, so complete the stop synchronously.
        inbox.drainEmergencyStopPreTick(minecraft, sessions.snapshot());
        overlay(minecraft, "CraftAgent: 緊急停止・ロック済み");
    }

    public void shutdown(Minecraft minecraft) {
        assertClientThread(minecraft);
        if (shutdown) {
            return;
        }
        shutdown = true;
        sessions.stopping();
        inbox.shutdown(minecraft, sessions.snapshot());
        memory.detachSession();
        arming.lock("client_shutdown");
        publishSession();
    }

    @Override
    public CompletionStage<RuntimeReply> submit(RuntimeCommand command, RuntimeCallContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        if (command instanceof EmergencyStop stop) {
            return inbox.requestEmergencyStop(stop.reason())
                    .thenApply(receipt -> RuntimeReply.success(Map.of(
                            "stop_requested", true,
                            "locked", receipt.locked(),
                            "released_inputs", receipt.inputsReleased(),
                            "discarded_pending_starts", receipt.discardedPendingStarts())));
        }
        if (!context.canBeginWork()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    RuntimeReply.failure("timeout", "The client-thread deadline expired", true));
        }

        var fence = publishedSession;
        return inbox.submit(command.toolName(), fence.generation(), context.deadlineNanos(), () -> {
                    if (!context.canBeginWork()) {
                        throw new ClientCommandInbox.CommandTimeoutException(command.toolName());
                    }
                    return executeOnClientThread(command);
                })
                .handle((data, failure) -> failure == null
                        ? RuntimeReply.success(data)
                        : mapFailure(failure));
    }

    private Map<String, Object> executeOnClientThread(RuntimeCommand command) {
        var minecraft = Minecraft.getInstance();
        assertClientThread(minecraft);
        var session = sessions.snapshot();
        return switch (command) {
            case GetStatus ignored -> status(minecraft, session);
            case GetSnapshot snapshot -> {
                requireReady(session);
                yield observations.getSnapshot(minecraft, session.clientTick(), snapshot.arguments());
            }
            case CompareBlockPlan compare -> {
                requireReady(session);
                yield blockPlans.compare(minecraft, session.clientTick(), compare.arguments());
            }
            case EmergencyStop ignored -> throw new AssertionError("emergency stop bypasses the normal queue");
        };
    }

    private Map<String, Object> status(Minecraft minecraft, WorldSessionTracker.Snapshot session) {
        var lock = arming.snapshot(session.worldSessionId(), System.nanoTime());
        var stats = memory.stats();
        Long unlockExpiryTick = lock.locked()
                ? null
                : session.clientTick() + Math.max(0L, lock.remainingNanos() / Duration.ofMillis(50).toNanos());

        var versions = new LinkedHashMap<String, Object>();
        versions.put("mcp", MCP_PROTOCOL_VERSION);
        versions.put("mod", modVersion);
        // The launcher string is NeoForge's launch target in dev/production and is not
        // the Minecraft data version advertised by the running game.
        versions.put("minecraft", SharedConstants.getCurrentVersion().name());
        versions.put("neoforge", neoForgeVersion);
        versions.put("adapter", null);

        var world = new LinkedHashMap<String, Object>();
        world.put("connected", session.worldReady());
        world.put("dimension", session.dimension());
        world.put("world_session_id", session.worldSessionId() == null ? null : session.worldSessionId().toString());

        var lockPayload = new LinkedHashMap<String, Object>();
        lockPayload.put("locked", lock.locked());
        lockPayload.put("unlock_expires_at_client_tick", unlockExpiryTick);
        lockPayload.put("reason", lock.lastLockReason());

        long evicted = stats.evictedBlocks() + stats.evictedEntities();
        var memoryPayload = new LinkedHashMap<String, Object>();
        memoryPayload.put("count", stats.retainedBlocks() + stats.retainedEntities());
        memoryPayload.put("retention_policy", "session_lru:block=" + stats.blockLimit() + ",entity=" + stats.entityLimit());
        memoryPayload.put("evicted_count", evicted);
        memoryPayload.put("oldest_retained_tick", stats.oldestRetainedTick() == 0 ? null : stats.oldestRetainedTick());
        memoryPayload.put("warning", evicted == 0 ? null : "old observations have been evicted");

        var result = new LinkedHashMap<String, Object>();
        result.put("versions", versions);
        result.put("world", world);
        result.put("lock", lockPayload);
        result.put("capability_profile", AVAILABLE_CAPABILITIES.stream().sorted().toList());
        var voiceChat = new LinkedHashMap<String, Object>();
        voiceChat.put("status", "unavailable");
        voiceChat.put("adapter_version", null);
        result.put("voice_chat", voiceChat);
        result.put("policies", Map.of("survival", "stop_and_notify", "completion", "stay"));
        result.put("active_routine", null);
        result.put("memory", memoryPayload);
        return result;
    }

    private void synchronizeWorld(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null || minecraft.gameMode == null
                || minecraft.getConnection() == null) {
            return;
        }
        var dimension = minecraft.level.dimension().identifier().toString();
        var before = sessions.snapshot();
        boolean changed = sessions.latchReady(dimension);
        var after = sessions.snapshot();
        if (changed) {
            memory.startSession(after.worldSessionId(), dimension);
            if (before.dimension() != null && !before.dimension().equals(dimension)) {
                arming.lock("dimension_changed");
                inputRelease.releaseAll(minecraft);
            }
        }
    }

    private static void requireReady(WorldSessionTracker.Snapshot session) {
        if (!session.worldReady()) {
            throw new MinecraftObservationService.ObservationUnavailableException(
                    "no_world", "No client world is ready");
        }
    }

    private static RuntimeReply mapFailure(Throwable failure) {
        var cause = unwrap(failure);
        if (cause instanceof ClientCommandInbox.CommandTimeoutException) {
            return RuntimeReply.failure("timeout", "The client-thread deadline expired", true);
        }
        if (cause instanceof ClientCommandInbox.CommandInvalidatedException) {
            return RuntimeReply.failure("unsafe_state", "The world or safety epoch changed before execution", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return RuntimeReply.failure("busy", "The bounded client command inbox is full", true);
        }
        if (cause instanceof MinecraftObservationService.ObservationUnavailableException unavailable) {
            return RuntimeReply.failure(unavailable.code(), unavailable.getMessage(), true);
        }
        if (cause instanceof IllegalArgumentException) {
            return RuntimeReply.failure("invalid_argument", publicMessage(cause), false);
        }
        return RuntimeReply.failure("internal_error", "The request failed; consult the local audit log", false);
    }

    private static Throwable unwrap(Throwable failure) {
        var result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static String publicMessage(Throwable failure) {
        var message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "The request arguments are invalid";
        }
        var normalized = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(512, normalized.length()));
    }

    private void publishSession() {
        publishedSession = sessions.snapshot();
    }

    private static void assertClientThread(Minecraft minecraft) {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("Minecraft state accessed outside the client thread");
        }
    }

    private static void overlay(Minecraft minecraft, String message) {
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal(message));
        }
    }

    public boolean paused() {
        return paused;
    }

    public WorldMemory memory() {
        return memory;
    }
}
