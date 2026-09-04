package dev.aod.mcmcp.fixture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

final class FixtureCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "mcmcp_fixture";

    private FixtureCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
                .executes(context -> execute(context.getSource(), FixtureArena::sendStatus))
                .then(Commands.literal("load")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.load(fixture);
                            success(context.getSource(), "arena loaded; observer baseline is ready");
                        })))
                .then(Commands.literal("status")
                        .executes(context -> execute(context.getSource(), FixtureArena::sendStatus)))
                .then(Commands.literal("random_ticks")
                        .then(Commands.literal("status")
                                .executes(context -> execute(
                                        context.getSource(), FixtureRandomTicks::status)))
                        .then(Commands.literal("accelerate")
                                .executes(context -> execute(
                                        context.getSource(), FixtureRandomTicks::accelerate)))
                        .then(Commands.literal("restore")
                                .executes(context -> execute(
                                        context.getSource(), FixtureRandomTicks::restore))))
                .then(Commands.literal("reset_player")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.resetInventoryAndStatus(fixture);
                            success(context.getSource(), "inventory and player status reset");
                        })))
                .then(Commands.literal("phase2")
                        .then(Commands.literal("regen")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase2Scenario.prepare(
                                            fixture, FixturePhase2Scenario.Mode.REGENERATE);
                                    success(context.getSource(),
                                            "Phase 2 target ready: stone, 8-tick regeneration");
                                })))
                        .then(Commands.literal("no_regen")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase2Scenario.prepare(
                                            fixture, FixturePhase2Scenario.Mode.NO_REGENERATION);
                                    success(context.getSource(),
                                            "Phase 2 target ready: stone, regeneration disabled");
                                })))
                        .then(Commands.literal("slow")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase2Scenario.prepare(
                                            fixture, FixturePhase2Scenario.Mode.SLOW_TARGET);
                                    success(context.getSource(),
                                            "Phase 2 target ready: obsidian slow/cancel fixture");
                                })))
                        .then(Commands.literal("status")
                                .executes(context -> execute(
                                        context.getSource(), FixturePhase2Scenario::sendStatus)))
                        .then(Commands.literal("off")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase2Scenario.stop();
                                    success(context.getSource(), "Phase 2 fixture stopped");
                                }))))
                .then(Commands.literal("phase3")
                        .then(phase3("navigate", FixturePhase3Scenario.Mode.NAVIGATE))
                        .then(phase3("break", FixturePhase3Scenario.Mode.BREAK))
                        .then(phase3("place", FixturePhase3Scenario.Mode.PLACE))
                        .then(phase3("lever", FixturePhase3Scenario.Mode.LEVER))
                        .then(phase3("cow", FixturePhase3Scenario.Mode.COW))
                        .then(phase3("reset", FixturePhase3Scenario.Mode.RESET)))
                .then(Commands.literal("phase4")
                        .then(phase4("all_satisfied", FixturePhase4Scenario.Mode.ALL_SATISFIED))
                        .then(phase4("mutations", FixturePhase4Scenario.Mode.MUTATIONS))
                        .then(phase4("waterlogged", FixturePhase4Scenario.Mode.WATERLOGGED))
                        .then(phase4("directional_stairs",
                                FixturePhase4Scenario.Mode.DIRECTIONAL_STAIRS))
                        .then(phase4("directional_stairs_matrix",
                                FixturePhase4Scenario.Mode.DIRECTIONAL_STAIRS_MATRIX))
                        .then(phase4("hopper", FixturePhase4Scenario.Mode.HOPPER))
                        .then(phase4("shortage", FixturePhase4Scenario.Mode.SHORTAGE))
                        .then(phase4("divergence", FixturePhase4Scenario.Mode.DIVERGENCE))
                        .then(phase4("hidden", FixturePhase4Scenario.Mode.HIDDEN))
                        .then(phase4("build_runner", FixturePhase4Scenario.Mode.BUILD_RUNNER))
                        .then(Commands.literal("introduce_divergence")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase4Scenario.introduceDivergence(fixture);
                                    success(context.getSource(),
                                            "Phase 4 guard cell changed outside routine ownership");
                                })))
                        .then(Commands.literal("reveal_hidden")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase4Scenario.revealHidden(fixture);
                                    success(context.getSource(), "Phase 4 hidden aperture opened");
                                })))
                        .then(Commands.literal("conceal_hidden")
                                .executes(context -> execute(context.getSource(), (fixture, output) -> {
                                    FixturePhase4Scenario.concealHidden(fixture);
                                    success(context.getSource(), "Phase 4 hidden aperture sealed");
                                }))))
                .then(Commands.literal("phase5")
                        .then(phase5("recipes", FixturePhase5Mode.RECIPES))
                        .then(phase5("craft", FixturePhase5Mode.CRAFT))
                        .then(phase5("smelt", FixturePhase5Mode.SMELT))
                        .then(phase5("warehouse_smelt", FixturePhase5Mode.WAREHOUSE_SMELT))
                        .then(phase5("label_transfer", FixturePhase5Mode.LABEL_TRANSFER))
                        .then(phase5("brew", FixturePhase5Mode.BREW))
                        .then(phase5("redstone", FixturePhase5Mode.REDSTONE))
                        .then(phase5("transfer", FixturePhase5Mode.TRANSFER))
                        .then(phase5("crop", FixturePhase5Mode.CROP))
                        .then(phase5("combined_wheat", FixturePhase5Mode.COMBINED_WHEAT))
                        .then(Commands.literal("combined_wheat_status")
                                .executes(context -> execute(context.getSource(),
                                        FixtureCombinedWheatScenario::sendStatus)))
                        .then(Commands.literal("combined_wheat_rollback")
                                .executes(context -> execute(context.getSource(),
                                        FixtureCombinedWheatScenario::rollback)))
                        .then(phase5("tree", FixturePhase5Mode.TREE))
                        .then(phase5("sleep", FixturePhase5Mode.SLEEP))
                        .then(phase5("survey", FixturePhase5Mode.SURVEY))
                        .then(phase5("generalization", FixturePhase5Mode.GENERALIZATION))
                        .then(phase5("cobblestone_generator",
                                FixturePhase5Mode.COBBLESTONE_GENERATOR))
                        .then(Commands.literal("cobblestone_generator_status")
                                .executes(context -> execute(context.getSource(),
                                        FixtureCobblestoneGeneratorScenario::sendStatus)))
                        .then(Commands.literal("cobblestone_generator_oracle")
                                .executes(context -> execute(context.getSource(),
                                        FixtureCobblestoneGeneratorScenario::sendOracle)))
                        .then(phase5("fishing", FixturePhase5Mode.FISHING))
                        .then(Commands.literal("fishing_status")
                                .executes(context -> execute(context.getSource(),
                                        FixtureFishingScenario::sendStatus)))
                        .then(Commands.literal("fishing_oracle")
                                .executes(context -> execute(context.getSource(),
                                        FixtureFishingScenario::sendOracle)))
                        .then(phase5("kill_zone", FixturePhase5Mode.KILL_ZONE))
                        .then(Commands.literal("kill_zone_status")
                                .executes(context -> execute(context.getSource(),
                                        FixtureKillZoneScenario::sendStatus)))
                        .then(Commands.literal("kill_zone_oracle")
                                .executes(context -> execute(context.getSource(),
                                        FixtureKillZoneScenario::sendOracle)))
                        .then(phase5("iron_farm", FixturePhase5Mode.IRON_FARM))
                        .then(Commands.literal("verify_tree")
                                .executes(context -> execute(
                                        context.getSource(), FixturePhase5Scenario::verifyTreeGate)))
                        .then(phase5("reset", FixturePhase5Mode.RESET)))
                .then(Commands.literal("expose_hidden")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.exposeHidden(fixture);
                            success(context.getSource(),
                                    "hidden cell exposed; a fresh observation may update remembered state");
                        })))
                .then(Commands.literal("conceal_hidden")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.concealHidden(fixture);
                            success(context.getSource(),
                                    "hidden cell sealed; observer should retain last-known state only");
                        })))
                .then(Commands.literal("mutate_hidden")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.mutateHidden(fixture);
                            success(context.getSource(),
                                    "sealed ground truth changed; observer must not learn it through the wall");
                        })))
                .then(Commands.literal("iron_farm_oracle")
                        .executes(context -> execute(
                                context.getSource(), FixtureIronFarmScenario::sendOracle)))
                .then(Commands.literal("iron_farm_activate")
                        .executes(context -> execute(
                                context.getSource(), FixtureIronFarmScenario::activate)))
                .then(Commands.literal("iron_farm_complete_for_evaluation")
                        .executes(context -> execute(
                                context.getSource(), FixtureIronFarmScenario::completeForEvaluation)))
                .then(Commands.literal("oracle")
                        .executes(context -> execute(context.getSource(), FixtureArena::sendOracle))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> phase3(
            String name, FixturePhase3Scenario.Mode mode) {
        return Commands.literal(name)
                .executes(context -> execute(context.getSource(), (fixture, output) ->
                        FixturePhase3Scenario.prepare(fixture, mode, output)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> phase4(
            String name, FixturePhase4Scenario.Mode mode) {
        return Commands.literal(name)
                .executes(context -> execute(context.getSource(), (fixture, output) ->
                        FixturePhase4Scenario.prepare(fixture, mode, output)));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> phase5(
            String name, FixturePhase5Mode mode) {
        return Commands.literal(name)
                .executes(context -> execute(context.getSource(), (fixture, output) ->
                        FixturePhase5Scenario.prepare(fixture, mode, output)));
    }

    private static int execute(CommandSourceStack source, FixtureAction action) {
        FixtureSecurity.Decision decision = FixtureSecurity.authorize(source);
        if (!decision.allowed()) {
            source.sendFailure(Component.literal("MCMCP fixture rejected: " + decision.rejection()));
            return 0;
        }

        try {
            action.run(decision.context(), component -> source.sendSuccess(() -> component, false));
            return 1;
        } catch (RuntimeException exception) {
            LOGGER.error("MCMCP fixture command failed", exception);
            source.sendFailure(Component.literal("MCMCP fixture failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    @FunctionalInterface
    private interface FixtureAction {
        void run(FixtureSecurity.Context context, java.util.function.Consumer<Component> output);
    }
}
