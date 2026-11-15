package dev.aod.mcmcp.client;

import net.minecraft.client.player.ClientInput;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentInputRoutingContractTest {
    @Test
    void repeatedAttackChargesNewDestroyStartsAndUseChargesOnlyAfterCooldown() throws Exception {
        var mode = minecraftClassNode("net/minecraft/client/multiplayer/MultiPlayerGameMode.class");
        assertThat(invocations(method(mode, "continueDestroyBlock")))
                .contains("net/minecraft/client/multiplayer/MultiPlayerGameMode#startDestroyBlock");
        method(mode, "startDestroyBlock", "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z");
        var hook = method(classNode("/dev/aod/mcmcp/mixin/client/MultiPlayerGameModeBoundedInputMixin.class"),
                "mcmcp$boundNewBreak");
        assertThat(injection(hook).values.toString()).contains("startDestroyBlock(", "cancellable, true", "require, 1");
        assertThat(invocations(hook)).containsSubsequence(
                "dev/aod/mcmcp/client/AgentInputState#attackActive",
                "dev/aod/mcmcp/client/AgentInputState#beginBoundedInput",
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable#setReturnValue");
        assertThat(invocations(method(classNode("/dev/aod/mcmcp/client/AgentUseInputChannel.class"), "onClientPostTick")))
                .containsSubsequence(
                        "dev/aod/mcmcp/mixin/client/MinecraftUseItemInvoker#mcmcp$getRightClickDelay",
                        "net/minecraft/client/player/LocalPlayer#isUsingItem",
                        "dev/aod/mcmcp/client/AgentInputState#beginBoundedInput",
                        "dev/aod/mcmcp/mixin/client/MinecraftUseItemInvoker#mcmcp$startUseItem");
    }

    @Test
    void ongoingUseHookOnlyInterceptsThePhysicalKeyReleaseInVanilla() throws Exception {
        var keybinds = method(minecraftClassNode("net/minecraft/client/Minecraft.class"), "handleKeybinds");
        assertThat(invocations(keybinds).stream().filter(call -> call.equals(
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#releaseUsingItem"))).hasSize(1);
        var hook = method(classNode("/dev/aod/mcmcp/mixin/client/MinecraftBoundedUseMixin.class"),
                "mcmcp$preserveOngoingUse");
        var redirect = Arrays.asList(hook.visibleAnnotations, hook.invisibleAnnotations).stream()
                .filter(java.util.Objects::nonNull).flatMap(java.util.Collection::stream)
                .filter(annotation -> annotation.desc.equals(
                "Lorg/spongepowered/asm/mixin/injection/Redirect;")).findFirst().orElseThrow();
        assertThat(redirect.values.toString()).contains("handleKeybinds", "require, 1", "expect, 1");
        assertThat(invocations(hook)).containsExactly(
                "dev/aod/mcmcp/client/AgentInputState#global",
                "dev/aod/mcmcp/client/AgentInputState#maintainsBoundedUse",
                "net/minecraft/client/multiplayer/MultiPlayerGameMode#releaseUsingItem");
        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).contains(
                    "\"client.MinecraftBoundedUseMixin\"", "\"client.MultiPlayerGameModeBoundedInputMixin\"");
        }
    }

    @Test
    void neoForgeEventExposesFinalClientInputAndModAppliesAgentStateThere() throws Exception {
        assertThat(MovementInputUpdateEvent.class.getMethod("getInput").getReturnType())
                .isEqualTo(ClientInput.class);

        var calls = invocations(method(
                classNode("/dev/aod/mcmcp/McmcpMod.class"),
                "onMovementInputUpdate"));
        assertThat(calls)
                .contains(
                        "net/neoforged/neoforge/client/event/MovementInputUpdateEvent#getInput",
                        "dev/aod/mcmcp/client/AgentMovementInput#apply")
                .noneMatch(call -> call.endsWith("#isWindowActive"))
                .noneMatch(call -> call.endsWith("#isMouseGrabbed"))
                .noneMatch(call -> call.contains("Screen#"));

        var postTickCalls = invocations(method(
                classNode("/dev/aod/mcmcp/McmcpMod.class"),
                "onPostTick"));
        assertThat(postTickCalls).containsSubsequence(
                "dev/aod/mcmcp/client/AgentBlockBreakChannel#onClientPostTick",
                "dev/aod/mcmcp/runtime/McmcpRuntime#onPostTick");
    }

    @Test
    void leasesNeverWritePhysicalKeyMappings() throws Exception {
        var movementCalls = invocations(method(
                classNode("/dev/aod/mcmcp/routine/MovementInputLease$VanillaMovementControl.class"),
                "apply",
                "(Ljava/util/Set;J)V"));
        assertThat(movementCalls)
                .contains("dev/aod/mcmcp/client/AgentInputState#publishMovement")
                .noneMatch(call -> call.equals("net/minecraft/client/KeyMapping#setDown"));

        var attackCalls = invocations(method(
                classNode("/dev/aod/mcmcp/routine/AttackInputLease$VanillaAttackControl.class"),
                "press"));
        assertThat(attackCalls)
                .contains("dev/aod/mcmcp/client/AgentInputState#publishAttack")
                .noneMatch(call -> call.equals("net/minecraft/client/KeyMapping#setDown"));

        var channelCalls = invocations(method(
                classNode("/dev/aod/mcmcp/client/AgentBlockBreakChannel.class"),
                "onClientPostTick"));
        assertThat(channelCalls)
                .contains("net/minecraft/client/multiplayer/MultiPlayerGameMode#continueDestroyBlock")
                .noneMatch(call -> call.equals("net/minecraft/client/KeyMapping#setDown"));
    }

    @Test
    void narrowMixinPreventsVanillaPhysicalReleaseFromStoppingAgentAttack() throws Exception {
        var hook = method(
                classNode("/dev/aod/mcmcp/mixin/client/MinecraftAttackInputMixin.class"),
                "mcmcp$keepAgentAttackSeparate");
        var inject = injection(hook);
        assertThat(inject.values.toString())
                .contains("continueAttack(Z)V")
                .contains("cancellable, true")
                .contains("require, 1")
                .contains("expect, 1");
        assertThat(invocations(hook))
                .contains(
                        "dev/aod/mcmcp/client/AgentInputState#attackActive",
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo#cancel");

        try (var stream = getClass().getResourceAsStream("/mcmcp.mixins.json")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"client.MinecraftAttackInputMixin\"");
        }
    }

    private static ClassNode minecraftClassNode(String resource) throws Exception {
        // NeoForge's test runtime omits physical-client classes; inspect the artifact used by compileJava.
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of(
                System.getProperty("mcmcp.projectDir"), "build/moddev/artifacts"))) {
            var jars = files.filter(path -> path.getFileName().toString().startsWith("minecraft-patched-")
                    && path.toString().endsWith(".jar")).toList();
            assertThat(jars).hasSize(1);
            try (var jar = new java.util.jar.JarFile(jars.getFirst().toFile());
                 var stream = jar.getInputStream(jar.getJarEntry(resource))) {
                var node = new ClassNode();
                new ClassReader(stream).accept(node, 0);
                return node;
            }
        }
    }

    private static ClassNode classNode(String resource) throws Exception {
        var node = new ClassNode();
        try (var stream = AgentInputRoutingContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).isNotNull();
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst().orElseThrow();
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst().orElseThrow();
    }

    private static AnnotationNode injection(MethodNode method) {
        return Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations).stream()
                .filter(annotations -> annotations != null)
                .flatMap(java.util.Collection::stream)
                .filter(annotation -> annotation.desc.equals(
                        "Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
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
}
