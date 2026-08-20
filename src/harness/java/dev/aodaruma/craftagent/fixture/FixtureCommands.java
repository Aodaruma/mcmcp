package dev.aodaruma.craftagent.fixture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

final class FixtureCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "craftagent_fixture";

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
                .then(Commands.literal("reset_player")
                        .executes(context -> execute(context.getSource(), (fixture, output) -> {
                            FixtureArena.resetInventoryAndStatus(fixture);
                            success(context.getSource(), "inventory and player status reset");
                        })))
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
                .then(Commands.literal("oracle")
                        .executes(context -> execute(context.getSource(), FixtureArena::sendOracle))));
    }

    private static int execute(CommandSourceStack source, FixtureAction action) {
        FixtureSecurity.Decision decision = FixtureSecurity.authorize(source);
        if (!decision.allowed()) {
            source.sendFailure(Component.literal("CraftAgent fixture rejected: " + decision.rejection()));
            return 0;
        }

        try {
            action.run(decision.context(), component -> source.sendSuccess(() -> component, false));
            return 1;
        } catch (RuntimeException exception) {
            LOGGER.error("CraftAgent fixture command failed", exception);
            source.sendFailure(Component.literal("CraftAgent fixture failed: " + exception.getMessage()));
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
