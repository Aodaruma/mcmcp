package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.dsl.ActionDsl;
import dev.aod.mcmcp.agent.dsl.ActionDslCompiler;
import dev.aod.mcmcp.routine.BlockTarget;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McmcpRuntimeKnownMenuTest {
    @Test
    void opaqueMenuOperationUsesOneStationaryBoundedAdapterRequest() {
        var operation = new ActionDsl.OperateKnownMenu(
                "transfer", "abcdefghijklmnopqrstuvwx");
        var request = McmcpRuntime.knownMenuRequest(
                operation, new BlockTarget("minecraft:overworld", 1, 64, 2));

        assertThat(request.kind()).isEqualTo("operate_known_menu");
        assertThat(request.parameters()).containsOnly(
                org.assertj.core.data.MapEntry.entry(
                        "operation_ref", "abcdefghijklmnopqrstuvwx"));
        assertThat(request.bounds().maxTravelBlocks()).isZero();
        assertThat(request.bounds().maxDurationSeconds()).isEqualTo(30);
        assertThat(request.expectedUnits()).isEqualTo(1);
        assertThat(McmcpRuntime.structuralPrimitiveCost(operation).orElseThrow())
                .isEqualTo(new ActionDslCompiler.Cost(
                        ActionDslCompiler.KNOWN_MENU_OPERATION_DURATION_MILLIS,
                        ActionDslCompiler.KNOWN_MENU_OPERATION_TICKS,
                        0, 0,
                        ActionDslCompiler.KNOWN_MENU_OPERATION_INTERACTIONS,
                        0, 0));
    }

    @Test
    void onlyTheSingleOpaqueMenuOperationBypassesMovementSafetyAdmission() {
        var operation = new ActionDsl.OperateKnownMenu(
                "transfer", "abcdefghijklmnopqrstuvwx");
        var menuOnly = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(operation));
        var delayed = new ActionDsl.Program(
                1,
                Optional.empty(),
                Set.of(ActionDsl.Capability.INVENTORY_TRANSFER),
                List.of(new ActionDsl.WaitTicks("wait", 1), operation));

        assertThat(McmcpRuntime.actionAdmissionRequiresLocalSafety(menuOnly)).isFalse();
        assertThat(McmcpRuntime.actionAdmissionRequiresLocalSafety(delayed)).isTrue();
    }

    @Test
    void knownMenuQuickMoveWaitsForServerSlotsInsteadOfPredictingThemLocally() throws Exception {
        var node = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/routine/MinecraftKnownMenuPort.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        var method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("dispatchServerConfirmedQuickMove"))
                .findFirst().orElseThrow();
        var calls = new ArrayList<String>();
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }

        assertThat(calls)
                .contains("dev/aod/mcmcp/routine/KnownMenuTransfers#dispatchServerConfirmedQuickMove")
                .doesNotContain(
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#handleContainerInput",
                        "net/minecraft/world/inventory/AbstractContainerMenu#clicked");
        // Follow the extracted shared implementation and preserve the server-only packet proof.
        var shared = new ClassNode();
        try (var stream = getClass().getResourceAsStream(
                "/dev/aod/mcmcp/routine/KnownMenuTransfers.class")) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(shared, 0);
        }
        var sharedDispatch = shared.methods.stream()
                .filter(candidate -> candidate.name.equals("dispatchServerConfirmedQuickMove"))
                .findFirst().orElseThrow();
        calls.clear();
        for (var instruction : sharedDispatch.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner + "#" + call.name);
            }
        }

        assertThat(calls)
                .contains(
                        "net/minecraft/network/HashedStack#create",
                        "net/minecraft/client/multiplayer/ClientPacketListener#send")
                .doesNotContain(
                        "net/minecraft/client/multiplayer/MultiPlayerGameMode#handleContainerInput",
                        "net/minecraft/world/inventory/AbstractContainerMenu#clicked");
    }
}
