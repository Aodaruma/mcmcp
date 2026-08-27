package dev.aod.mcmcp.agent.safety;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalObservationMixinContractTest {
    @Test
    void collisionTraceWrapsEntityMoveCollideAndCallsOriginalExactlyOnce() throws Exception {
        var hook = method(
                classNode("/dev/aod/mcmcp/mixin/client/EntityAgentCollisionMixin.class"),
                "mcmcp$recordAgentCollision");
        var wrap = annotation(
                hook,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

        assertThat(wrap.values.toString())
                .contains("move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(at(wrap).values.toString())
                .contains("INVOKE")
                .contains("Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;");
        assertThat(invocations(hook))
                .filteredOn(call -> call.equals(
                        "com/llamalad7/mixinextras/injector/wrapoperation/Operation#call"))
                .hasSize(1);
        assertThat(invocations(hook))
                .contains("dev/aod/mcmcp/agent/safety/AgentMovementTrace#recordCollision");
        assertThat(fields(hook)).contains("net/minecraft/world/entity/MoverType#SELF");
    }

    @Test
    void exactGuardPreviewsResolvedStepAndCanRemoveOnlyAgentContribution() throws Exception {
        var hook = method(
                classNode("/dev/aod/mcmcp/mixin/client/EntityAgentCollisionMixin.class"),
                "mcmcp$guardAgentMovementBeforeCollision");
        var wrap = annotation(
                hook,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");

        assertThat(at(wrap).values.toString())
                .contains("maybeBackOffFromEdge")
                .contains("MoverType");
        assertThat(invocations(hook)).contains(
                "dev/aod/mcmcp/mixin/client/EntityCollisionInvoker#mcmcp$collide",
                "dev/aod/mcmcp/agent/safety/LocalObservationVolume#canPreviewGoalMovement",
                "dev/aod/mcmcp/agent/safety/LocalObservationVolume#verifiesGoalResolvedMovement",
                "dev/aod/mcmcp/agent/safety/LocalObservationVolume#verifiesNavigationResolvedMovement",
                "dev/aod/mcmcp/agent/safety/LocalObservationVolume#verifiesRecoveryResolvedMovement",
                "dev/aod/mcmcp/runtime/ClientReconciliationSignals#currentSnapshot",
                "dev/aod/mcmcp/client/AgentInputState#movementBoundary",
                "dev/aod/mcmcp/client/AgentInputState#completeExpiredMovementBoundary",
                "dev/aod/mcmcp/client/AgentInputState#acceptGoalMovement",
                "dev/aod/mcmcp/client/AgentInputState#rejectGoalMovement");
        assertThat(invocations(hook))
                .filteredOn(call -> call.equals(
                        "com/llamalad7/mixinextras/injector/wrapoperation/Operation#call"))
                .hasSize(4);
    }

    @Test
    void tickBoundaryMixinCapturesMovementAndRuntimePublishesOnItsSessionClock() throws Exception {
        var node = classNode(
                "/dev/aod/mcmcp/mixin/client/LocalPlayerMovementTickMixin.class");
        var begin = method(node, "mcmcp$beginAgentMovementTick");
        var end = method(node, "mcmcp$endAgentMovementTick");

        var beginInject = annotation(begin, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        var endInject = annotation(end, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertThat(beginInject.values.toString())
                .contains("tick()V", "require, 1", "expect, 1");
        assertThat(endInject.values.toString())
                .contains("tick()V", "require, 1", "expect, 1");
        assertThat(at(beginInject).values.toString()).contains("HEAD");
        assertThat(at(endInject).values.toString()).contains("RETURN");
        assertThat(invocations(end))
                .contains(
                        "dev/aod/mcmcp/agent/safety/AgentMovementTrace#endTick",
                        "dev/aod/mcmcp/client/AgentInputState#endPlayerMovementTick")
                .noneMatch(call -> call.contains("LocalObservationVolume#onPlayerTick"));
        assertThat(invocations(begin))
                .contains("dev/aod/mcmcp/client/AgentInputState#beginPlayerMovementTick");
        var collect = method(
                classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class"),
                "collectAgentObservation");
        assertThat(invocations(collect))
                .contains("dev/aod/mcmcp/agent/safety/LocalObservationVolume#observe");
    }

    @Test
    void targetMethodAndMoverTypeStillMatchThePinnedMinecraftApi() throws Exception {
        var collide = Entity.class.getDeclaredMethod("collide", Vec3.class);
        var backOff = Entity.class.getDeclaredMethod(
                "maybeBackOffFromEdge", Vec3.class, MoverType.class);

        assertThat(Modifier.isPrivate(collide.getModifiers())).isTrue();
        assertThat(Modifier.isProtected(backOff.getModifiers())).isTrue();
        assertThat(Entity.class.getDeclaredMethod("move", MoverType.class, Vec3.class)).isNotNull();
        assertThat(Arrays.asList(MoverType.values())).contains(MoverType.SELF);

        var collectColliders = Entity.class.getDeclaredMethod(
                "collectCollidersIgnoringWorldBorder",
                Entity.class,
                Level.class,
                List.class,
                AABB.class);
        var collectStepHeights = Entity.class.getDeclaredMethod(
                "collectCandidateStepUpHeights",
                AABB.class,
                List.class,
                float.class,
                float.class);
        var collideWithShapes = Entity.class.getDeclaredMethod(
                "collideWithShapes",
                Vec3.class,
                AABB.class,
                List.class);
        assertThat(List.of(collectColliders, collectStepHeights, collideWithShapes))
                .allMatch(method -> Modifier.isPrivate(method.getModifiers()))
                .allMatch(method -> Modifier.isStatic(method.getModifiers()));
    }

    @Test
    void mixinsAreRegisteredInTheClientList() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"client.EntityAgentCollisionMixin\"")
                    .contains("\"client.EntityCollisionInvoker\"")
                    .contains("\"client.LivingEntityAgentMovementMixin\"")
                    .contains("\"client.LocalPlayerMovementTickMixin\"");
        }
    }

    @Test
    void samplerUsesVanillaShapesSupportAndFluidTypeWithoutRawBlockIdentity() throws Exception {
        var node = classNode("/dev/aod/mcmcp/agent/safety/LocalObservationVolume.class");
        var calls = node.methods.stream()
                .flatMap(method -> invocations(method).stream())
                .toList();

        assertThat(calls)
                .anyMatch(call -> call.endsWith("#findSupportingBlock"))
                .anyMatch(call -> call.endsWith("#collidesWithSuffocatingBlock"))
                .contains("net/minecraft/world/entity/Entity#collideBoundingBox")
                .anyMatch(call -> call.endsWith("#getAABB"))
                .anyMatch(call -> call.endsWith("#getFluidType"))
                .anyMatch(call -> call.endsWith("#getBounceRestitution"))
                .noneMatch(call -> call.contains("BuiltInRegistries"));
    }

    @Test
    void volumeRunsEveryPlayerTickWhileActualTraceRemainsAgentGated() throws Exception {
        var volume = classNode(
                "/dev/aod/mcmcp/agent/safety/LocalObservationVolume.class");
        var onTickCalls = volume.methods.stream()
                .filter(method -> method.name.equals("onPlayerTick"))
                .flatMap(method -> invocations(method).stream())
                .toList();
        assertThat(onTickCalls)
                .contains("dev/aod/mcmcp/agent/safety/LocalObservationVolume#observe")
                .noneMatch(call -> call.contains("AgentInputState"));

        var begin = method(
                classNode("/dev/aod/mcmcp/agent/safety/AgentMovementTrace.class"),
                "beginTick");
        assertThat(invocations(begin))
                .contains("dev/aod/mcmcp/agent/safety/AgentMovementTrace#agentMovementActive");
    }

    @Test
    void hypotheticalResolverReusesAllPrivateVanillaStepUpHelpers() throws Exception {
        var node = classNode(
                "/dev/aod/mcmcp/agent/safety/VanillaCollisionResolver.class");
        var calls = node.methods.stream()
                .flatMap(method -> invocations(method).stream())
                .toList();

        assertThat(calls).contains(
                "net/minecraft/world/entity/Entity#collideBoundingBox",
                "dev/aod/mcmcp/mixin/client/EntityCollisionInvoker#mcmcp$collectColliders",
                "dev/aod/mcmcp/mixin/client/EntityCollisionInvoker#mcmcp$collectCandidateStepUpHeights",
                "dev/aod/mcmcp/mixin/client/EntityCollisionInvoker#mcmcp$collideWithShapes")
                .anyMatch(call -> call.endsWith("#maxUpStep"))
                .anyMatch(call -> call.endsWith("#onGround"));
    }

    @Test
    void exactMovementGuardsDoNotUseCandidateGraphLandingProjection() throws Exception {
        var volume = classNode("/dev/aod/mcmcp/agent/safety/LocalObservationVolume.class");

        assertThat(invocations(method(volume, "verifiesGoalResolvedMovement")))
                .contains("dev/aod/mcmcp/agent/safety/LocalObservationVolume#evaluateResolvedHypothetical")
                .doesNotContain("dev/aod/mcmcp/agent/safety/LocalObservationVolume#evaluateHypothetical");
        assertThat(invocations(method(volume, "verifiesNavigationResolvedMovement")))
                .contains("dev/aod/mcmcp/agent/safety/LocalObservationVolume#evaluateResolvedHypothetical")
                .doesNotContain("dev/aod/mcmcp/agent/safety/LocalObservationVolume#evaluateHypothetical");
    }

    @Test
    void executorFencesItsCommandPreviewWhileTheMixinGuardsResolvedMovement() throws Exception {
        var driveNavigationWaypoint = method(
                classNode("/dev/aod/mcmcp/agent/action/MinecraftActionPrimitiveExecutor.class"),
                "driveNavigationWaypoint");

        assertThat(invocations(driveNavigationWaypoint))
                .contains("dev/aod/mcmcp/agent/action/MinecraftActionPrimitiveExecutor#commandDirection")
                .contains("dev/aod/mcmcp/agent/safety/LocalObservationVolume#canPreviewGoalMovement")
                .doesNotContain(
                        "dev/aod/mcmcp/agent/safety/LocalObservationVolume#verifiesGoalHorizontalMovement");
    }

    @Test
    void preTickAccountsFinalMotionBeforeAnyControlCanTerminateTheAction() throws Exception {
        var preTick = method(
                classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class"),
                "onPreTick");
        var calls = invocations(preTick);

        assertThat(calls.indexOf("dev/aod/mcmcp/runtime/McmcpRuntime#recordPendingAgentMotion"))
                .isLessThan(calls.indexOf(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick"));
        assertThat(calls.indexOf(
                "dev/aod/mcmcp/runtime/ClientCommandInbox#drainEmergencyStopPreTick"))
                .isLessThan(calls.indexOf(
                        "dev/aod/mcmcp/runtime/ClientCommandInbox#drainControlsPreTick"));
        assertThat(calls.indexOf(
                "dev/aod/mcmcp/runtime/ClientCommandInbox#drainControlsPreTick"))
                .isLessThan(calls.indexOf("dev/aod/mcmcp/runtime/McmcpRuntime#tickAgentAction"));
    }

    @Test
    void worldBoundaryCleanupDiscardsEveryRetainedActionRecord() throws Exception {
        var clear = method(
                classNode("/dev/aod/mcmcp/runtime/McmcpRuntime.class"),
                "clearAgentSessionState");

        assertThat(invocations(clear))
                .contains("dev/aod/mcmcp/agent/action/AgentActionStore#clear");
    }

    @Test
    void serverVelocityReplacementDiscardsResidualButExplosionAdditionDoesNot() throws Exception {
        var packets = classNode(
                "/dev/aod/mcmcp/mixin/client/ClientPacketListenerMixin.class");

        assertThat(invocations(method(packets, "mcmcp$localPlayerMotion")))
                .contains("dev/aod/mcmcp/client/AgentInputState#discardTrackedAgentVelocity");
        assertThat(invocations(method(packets, "mcmcp$positionCorrection")))
                .contains("dev/aod/mcmcp/client/AgentInputState#discardTrackedAgentVelocity");
        assertThat(invocations(method(packets, "mcmcp$explosionMotion")))
                .noneMatch(call -> call.contains("AgentInputState#discardTrackedAgentVelocity"));
    }

    @Test
    void persistentAgentVelocityUsesVanillasLandAndAirScaleSites() throws Exception {
        var living = classNode(
                "/dev/aod/mcmcp/mixin/client/LivingEntityAgentMovementMixin.class");
        var capture = method(living, "mcmcp$captureAgentAirFactors");
        var discard = method(living, "mcmcp$trackAgentAirWithoutFriction");
        var friction = method(living, "mcmcp$trackAgentAirFriction");

        assertThat(at(annotation(capture,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))
                .values.toString()).contains("handleRelativeFrictionAndCalculateMovement");
        assertThat(at(annotation(discard,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))
                .values.toString()).contains("setDeltaMovement(DDD)V", "ordinal, 0");
        assertThat(at(annotation(friction,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))
                .values.toString()).contains("setDeltaMovement(DDD)V", "ordinal, 1");
        assertThat(invocations(discard))
                .contains("dev/aod/mcmcp/client/AgentInputState#scaleAgentVelocity");
        assertThat(invocations(friction))
                .contains("dev/aod/mcmcp/client/AgentInputState#scaleAgentVelocity");

        var entity = method(
                classNode("/dev/aod/mcmcp/mixin/client/EntityAgentCollisionMixin.class"),
                "mcmcp$trackAgentBlockSpeedFactor");
        assertThat(at(annotation(entity,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"))
                .values.toString()).contains("multiply(DDD)");
        assertThat(invocations(entity))
                .contains("dev/aod/mcmcp/client/AgentInputState#scaleAgentVelocity");
    }

    @Test
    void executorChecksTheFreshHardDeadlineAtEachOutputBoundary() throws Exception {
        var executor = classNode(
                "/dev/aod/mcmcp/agent/action/MinecraftActionPrimitiveExecutor.class");
        var navigation = invocations(method(executor, "driveNavigationWaypoint"));
        var face = invocations(method(executor, "tickFace"));

        assertThat(navigation.indexOf("java/util/function/BooleanSupplier#getAsBoolean"))
                .isLessThan(navigation.indexOf(
                        "dev/aod/mcmcp/routine/MovementInputLease#setDesired"));
        assertThat(navigation.lastIndexOf("java/util/function/BooleanSupplier#getAsBoolean"))
                .isLessThan(navigation.indexOf(
                        "dev/aod/mcmcp/routine/MovementInputLease#heartbeat"));
        assertThat(face.indexOf("java/util/function/BooleanSupplier#getAsBoolean"))
                .isLessThan(face.indexOf(
                        "dev/aod/mcmcp/agent/action/MinecraftActionPrimitiveExecutor#turn"));
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = LocalObservationMixinContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        return Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(annotations -> annotations != null)
                .flatMap(java.util.Collection::stream)
                .filter(annotation -> annotation.desc.equals(descriptor))
                .findFirst()
                .orElseThrow();
    }

    private static AnnotationNode at(AnnotationNode injection) {
        for (int index = 0; index < injection.values.size(); index += 2) {
            if (!injection.values.get(index).equals("at")) {
                continue;
            }
            var value = injection.values.get(index + 1);
            if (value instanceof AnnotationNode annotation) {
                return annotation;
            }
            if (value instanceof List<?> annotations
                    && !annotations.isEmpty()
                    && annotations.getFirst() instanceof AnnotationNode annotation) {
                return annotation;
            }
        }
        throw new AssertionError("injection has no @At value");
    }

    private static List<String> invocations(MethodNode method) {
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }
        return List.copyOf(calls);
    }

    private static List<String> fields(MethodNode method) {
        var fields = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                fields.add(field.owner + "#" + field.name);
            }
        }
        return List.copyOf(fields);
    }
}
