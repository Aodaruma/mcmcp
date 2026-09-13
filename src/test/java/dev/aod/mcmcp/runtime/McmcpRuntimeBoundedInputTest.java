package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.routine.BlockStateFingerprint;
import dev.aod.mcmcp.client.AgentInputState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeBoundedInputTest {
    private static final BlockPos SNOW_POSITION = new BlockPos(1, 64, 2);
    private static final BlockStateFingerprint SNOW = new BlockStateFingerprint("minecraft:snow", Map.of("layers", "1"));

    @Test
    void faceOptOutAcceptsEveryRealFaceOfTheSameBlockAndDefaultStillRejectsSideFaces() {
        var relaxed = snowGuard(false, null);
        var strict = new ActionDsl.ExactBlockTargetGuard(relaxed.target(), relaxed.face(), "minecraft:snow", null);
        for (var face : Direction.values()) {
            assertThat(BoundedInputExecution.boundedTargetMismatch(relaxed, hit(SNOW_POSITION, face), () -> SNOW)).isNull();
            assertThat(BoundedInputExecution.boundedTargetMismatch(strict, hit(SNOW_POSITION, face), () -> SNOW))
                    .isEqualTo(face == Direction.UP ? null : "target_face_changed");
        }
    }

    @Test
    void relaxedFacesNeverAllowAnotherTargetOrNonBlockHitAndDoNotReadItsState() {
        var guard = snowGuard(false, null);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<BlockStateFingerprint> state = () -> { reads.incrementAndGet(); return SNOW; };
        assertThat(BoundedInputExecution.boundedTargetMismatch(guard, hit(SNOW_POSITION.east(), Direction.EAST), state))
                .isEqualTo("target_position_changed");
        var entity = new HitResult(Vec3.ZERO) { @Override public Type getType() { return Type.ENTITY; } };
        for (var focus : List.of(entity, BlockHitResult.miss(Vec3.ZERO, Direction.EAST, SNOW_POSITION))) {
            assertThat(BoundedInputExecution.boundedTargetMismatch(guard, focus, state)).isEqualTo("target_not_focused");
        }
        assertThat(BoundedInputExecution.boundedTargetMismatch(guard, null, state)).isEqualTo("target_not_focused");
        assertThat(reads).hasValue(0);
        assertThat(BoundedInputExecution.boundedTargetMismatch(guard, hit(SNOW_POSITION, Direction.EAST),
                () -> new BlockStateFingerprint("minecraft:stone", Map.of()))).isEqualTo("target_block_changed");
        var strictState = snowGuard(false, new ActionDsl.BlockStateSpec("minecraft:snow", Map.of("layers", "2")));
        assertThat(BoundedInputExecution.boundedTargetMismatch(strictState, hit(SNOW_POSITION, Direction.EAST), () -> SNOW))
                .isEqualTo("target_state_changed");
    }

    @Test
    void repeatingCrossFaceAttackWaitsForTheSameBlockThenResumesWithoutChargingTheGap() {
        var focus = new java.util.concurrent.atomic.AtomicReference<HitResult>(hit(SNOW_POSITION, Direction.EAST));
        var block = new java.util.concurrent.atomic.AtomicReference<>(SNOW);
        var guard = snowGuard(false, null);
        var state = new AgentInputState();
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<AgentInputState.BoundedDispatchDecision> check = () ->
                BoundedInputExecution.boundedTargetMismatch(guard, focus.get(), block::get) == null
                        ? AgentInputState.BoundedDispatchDecision.ALLOW : AgentInputState.BoundedDispatchDecision.WAIT;
        // The same matcher admits the first east-face hit before the repeating guard is installed.
        assertThat(check.get()).isEqualTo(AgentInputState.BoundedDispatchDecision.ALLOW);
        state.setRepeatingBoundedDispatchGuard(check, () -> { starts.incrementAndGet(); return true; });
        state.publishAttack(Long.MAX_VALUE);
        assertThat(state.beginBoundedInput()).isTrue();
        focus.set(BlockHitResult.miss(Vec3.ZERO, Direction.EAST, SNOW_POSITION));
        assertThat(state.beginBoundedInput()).isFalse();
        focus.set(hit(SNOW_POSITION, Direction.NORTH));
        block.set(new BlockStateFingerprint("minecraft:stone", Map.of()));
        assertThat(state.beginBoundedInput()).isFalse();
        assertThat(starts).hasValue(1);
        assertThat(state.boundedDispatchRejected()).isFalse();
        block.set(SNOW);
        assertThat(state.beginBoundedInput()).isTrue();
        assertThat(starts).hasValue(2);
    }

    private static ActionDsl.ExactBlockTargetGuard snowGuard(boolean matchFace, ActionDsl.BlockStateSpec state) {
        return new ActionDsl.ExactBlockTargetGuard(new ActionDsl.Position("minecraft:overworld", 1, 64, 2),
                ActionDsl.BlockFace.UP, "minecraft:snow", state, matchFace);
    }

    private static BlockHitResult hit(BlockPos position, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(position), face, position, false);
    }

    @Test
    void repeatStartsChargeTheActionLedgerWithoutClaimingConfirmedBreaks() {
        var json = repeatRequest(4096);
        var request = dev.aod.mcmcp.agent.dsl.ActionDslParser.parse(json);
        var program = dev.aod.mcmcp.agent.dsl.ActionDslCompiler.compile(request, ignored -> Optional.empty(),
                java.util.Set.of(ActionDsl.Capability.BLOCK_BREAK));
        var store = new dev.aod.mcmcp.agent.action.AgentActionStore();
        var accepted = store.start(program, dev.aod.mcmcp.agent.dsl.ActionDslSource.capture(json), java.time.Instant.EPOCH);
        store.markRunning(accepted.actionId());
        var action = store.active().orElseThrow();
        var execution = new BoundedInputExecution(new Object(), java.util.UUID.randomUUID(),
                new ScreenOwnershipSignals(new ContainerSyncSignals()), store);
        var hold = (ActionDsl.HoldBoundedInputs) request.program().body().getFirst();
        for (int tick = 0; tick < 4096; tick++) {
            assertThat(execution.reserveInputStart(action, hold)).isTrue();
            // A second start in the same tick cannot exceed the duration-derived proof.
            assertThat(execution.reserveInputStart(action, hold)).isFalse();
            store.recordTick(action.actionId());
        }
        assertThat(execution.reserveInputStart(action, hold)).isFalse();
        var snapshot = store.get(action.actionId());
        assertThat(snapshot.progress().interactions()).isEqualTo(4096);
        assertThat(snapshot.progress().ticks()).isEqualTo(4096);
        assertThat(snapshot.progress().blocksBroken()).isZero();
        assertThat(snapshot.effects()).isEmpty();
    }

    @Test
    void fullDayLedgerRemainsBoundedAndDoesNotOverflowOrClaimEffects() {
        var json = repeatRequest(1_728_000);
        var request = dev.aod.mcmcp.agent.dsl.ActionDslParser.parse(json);
        var program = dev.aod.mcmcp.agent.dsl.ActionDslCompiler.compile(request, ignored -> Optional.empty(),
                java.util.Set.of(ActionDsl.Capability.BLOCK_BREAK));
        var store = new dev.aod.mcmcp.agent.action.AgentActionStore();
        var accepted = store.start(program, dev.aod.mcmcp.agent.dsl.ActionDslSource.capture(json), java.time.Instant.EPOCH);
        store.markRunning(accepted.actionId());
        for (int i = 0; i < 1_728_000; i++) store.recordInteraction(accepted.actionId());
        var snapshot = store.get(accepted.actionId());
        assertThat(snapshot.progress().interactions()).isEqualTo(1_728_000);
        assertThat(snapshot.progress().blocksBroken()).isZero();
        assertThat(snapshot.effects()).isEmpty();
        assertThat(snapshot.trace()).hasSizeLessThan(10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.recordInteraction(accepted.actionId()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static com.google.gson.JsonObject repeatRequest(int ticks) {
        return com.google.gson.JsonParser.parseString("""
                {"schema_version":1,"program":{"dsl_version":1,"capabilities":["block_break"],
                  "body":[{"id":"hold","op":"hold_bounded_inputs","inputs":["attack"],
                    "duration_ticks":%d,"repeat_target":true,
                    "target_guard":{"target":{"dimension":"minecraft:overworld","x":1,"y":64,"z":2},
                      "face":"up","expected_state":null,"expected_block":"minecraft:snow"},
                    "selected_item":"minecraft:wooden_shovel"}]},
                 "budget":{"max_duration_ms":%d,"max_ticks":%d,"max_distance_blocks":0,
                   "max_camera_degrees":0,"max_interactions":%d,"max_blocks_broken":%d,"max_blocks_placed":0}}
                """.formatted(ticks, ticks * 50L, ticks, ticks, ticks)).getAsJsonObject();
    }

    @Test
    void runtimeRetainsTheFullTwentyFourHourStructuralCost() {
        var hold = new ActionDsl.HoldBoundedInputs(
                "hold", List.of(ActionDsl.BoundedInput.USE), 1_728_000L,
                Optional.of(new ActionDsl.ExactBlockTargetGuard(
                        new ActionDsl.Position("minecraft:overworld", 1, 64, 2),
                        ActionDsl.BlockFace.UP,
                        new ActionDsl.BlockStateSpec("minecraft:note_block", Map.of(
                                "instrument", "harp", "note", "0", "powered", "false")))),
                Optional.of("minecraft:fishing_rod"));

        assertThat(ActionPlanning.structuralPrimitiveCost(hold).orElseThrow())
                .isEqualTo(new dev.aod.mcmcp.agent.dsl.ActionDslCompiler.Cost(
                        86_400_000L, 1_728_000L, 0.0D, 0.0D, 1L, 0L, 0L));
    }
}
