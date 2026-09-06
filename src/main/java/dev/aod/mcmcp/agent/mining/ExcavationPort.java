package dev.aod.mcmcp.agent.mining;

import dev.aod.mcmcp.agent.action.KnownConstructionAttempt;
import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.BlockStateFingerprint;

import java.util.List;
import java.util.Objects;

/**
 * Normal-player adapter. Inspections must use current policy fog/LOS or local movement
 * evidence, never arbitrary reads of sealed cells. Dispatch revalidates its exact proof.
 */
public interface ExcavationPort {
    BlockInspection inspectBlock(TunnelGeometry.Cell block, long minTick, long minRevision);
    void beginBreak(Witness witness);
    OperationResult pollBreak();
    MoveInspection inspectMove(TunnelGeometry.Cell from, TunnelGeometry.Cell to, long minTick, long minRevision);
    void beginMove(TunnelGeometry.Cell to);
    OperationResult pollMove();
    StopReason safety();

    /** False retains the same owned attempts for a later cleanup retry, never redispatch. */
    boolean release();

    /** Drain once, including confirmed/unknown effects obtained while releasing an attempt. */
    default List<KnownConstructionAttempt.EffectDelta> drainEffects() { return List.of(); }

    enum BlockStatus { CLEAR, BREAKABLE, WAIT, STOP }
    enum Readiness { READY, WAIT, STOP }
    enum OperationStatus { RUNNING, SUCCEEDED, FAILED, UNKNOWN }
    enum StopReason {
        NONE, CANCELLED, DEADLINE, OBSERVATION_TIMEOUT, RENDERER_GAP, UNKNOWN_BLOCK,
        UNSUPPORTED_BLOCK, FLUID, FALLING_BLOCK, UNSAFE_FLOOR, UNSAFE_MOVEMENT,
        THREAT, HEALTH_CHANGED, TOOL_UNAVAILABLE, INVENTORY_FULL, WORLD_CHANGED,
        CONTROL_LOST, SAFETY_CHANGED, TARGET_CHANGED, SERVER_DENIED, UNKNOWN_EFFECT,
        BUDGET, ADAPTER_FAILURE, RELEASE_FAILED
    }

    record Witness(TunnelGeometry.Cell cell, ActionDsl.BlockFace face,
            BlockStateFingerprint state, long observedTick, long worldRevision) {
        public Witness {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(state, "state");
            if (observedTick < 0 || worldRevision < 0) throw new IllegalArgumentException("invalid witness clock");
        }
    }

    record BlockInspection(BlockStatus status, Witness witness, long observedTick,
            long worldRevision, StopReason reason) {
        public BlockInspection {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            if ((status == BlockStatus.BREAKABLE) != (witness != null)
                    || observedTick < -1 || worldRevision < -1
                    || (status == BlockStatus.CLEAR || status == BlockStatus.BREAKABLE)
                            && (observedTick < 0 || worldRevision < 0 || reason != StopReason.NONE)
                    || status == BlockStatus.STOP && reason == StopReason.NONE
                    || witness != null && (witness.observedTick() != observedTick
                            || witness.worldRevision() != worldRevision)) {
                throw new IllegalArgumentException("invalid block inspection");
            }
        }

        public static BlockInspection clear(long tick, long revision) {
            return new BlockInspection(BlockStatus.CLEAR, null, tick, revision, StopReason.NONE);
        }

        public static BlockInspection breakable(Witness witness) {
            return new BlockInspection(BlockStatus.BREAKABLE, witness,
                    witness.observedTick(), witness.worldRevision(), StopReason.NONE);
        }

        public static BlockInspection waiting(StopReason reason) {
            return new BlockInspection(BlockStatus.WAIT, null, -1, -1, reason);
        }

        public static BlockInspection stopped(StopReason reason) {
            return new BlockInspection(BlockStatus.STOP, null, -1, -1, reason);
        }
    }

    record MoveInspection(Readiness status, long observedTick, long worldRevision, StopReason reason) {
        public MoveInspection {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
            if (observedTick < -1 || worldRevision < -1
                    || status == Readiness.READY && (observedTick < 0 || worldRevision < 0 || reason != StopReason.NONE)
                    || status == Readiness.STOP && reason == StopReason.NONE) {
                throw new IllegalArgumentException("invalid movement inspection");
            }
        }

        public static MoveInspection ready(long tick, long revision) {
            return new MoveInspection(Readiness.READY, tick, revision, StopReason.NONE);
        }

        public static MoveInspection waiting(StopReason reason) {
            return new MoveInspection(Readiness.WAIT, -1, -1, reason);
        }

        public static MoveInspection stopped(StopReason reason) {
            return new MoveInspection(Readiness.STOP, -1, -1, reason);
        }
    }

    /** SUCCEEDED break means authoritative destruction, not physical drop collection. */
    record OperationResult(OperationStatus status, long clientTick, long worldRevision,
            List<KnownConstructionAttempt.EffectDelta> effects) {
        public OperationResult {
            Objects.requireNonNull(status, "status");
            effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
            if (clientTick < 0 || worldRevision < 0 || effects.size() > 2) {
                throw new IllegalArgumentException("invalid excavation operation result");
            }
        }
    }
}
