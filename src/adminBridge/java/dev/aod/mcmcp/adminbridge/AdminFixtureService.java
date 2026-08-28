package dev.aod.mcmcp.adminbridge;

import com.mojang.brigadier.ParseResults;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Loads scripts off-thread and serializes every world operation onto the integrated server. */
final class AdminFixtureService implements AdminBridgeApi {
    private static final long SERVER_DISPATCH_SECONDS = 10L;

    private final FixtureScriptLoader loader;
    private final AdminRandomTickLease randomTickLease;
    private IntegratedServer sessionServer;
    private Object sessionLevel;
    private String worldSessionId;

    AdminFixtureService(FixtureScriptLoader loader, AdminRandomTickLease randomTickLease) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.randomTickLease = Objects.requireNonNull(randomTickLease, "randomTickLease");
    }

    @Override
    public FixtureScript validate(String fixtureId) throws AdminBridgeException {
        try {
            return loader.load(fixtureId);
        } catch (FixtureFormatException failure) {
            throw new AdminBridgeException(failure.code(), failure);
        }
    }

    @Override
    public Status status() throws AdminBridgeException {
        return onServer(context -> {
            String session = bindSession(context);
            var lease = randomTickLease.snapshot();
            return new Status("READY", session, context.player().getX(), context.player().getY(),
                    context.player().getZ(), leaseStatus(lease));
        });
    }

    @Override
    public ApplyResult apply(String fixtureId, String expectedHash, String expectedWorldSession)
            throws AdminBridgeException {
        FixtureScript script = validate(fixtureId);
        if (expectedHash == null || !expectedHash.equals(script.sha256())) {
            throw new AdminBridgeException("fixture_hash_mismatch");
        }
        return onServer(context -> applyOnServer(context, script, expectedWorldSession));
    }

    private ApplyResult applyOnServer(
            AdminBridgeSecurity.Context context, FixtureScript script, String expectedWorldSession) {
        String session = bindSession(context);
        if (expectedWorldSession == null || !expectedWorldSession.equals(session)) {
            throw new ServiceFailure("world_session_mismatch");
        }
        if (!"minecraft:overworld".equals(script.manifest().dimension())) {
            throw new ServiceFailure("dimension_forbidden");
        }
        CommandSourceStack source = context.player().createCommandSourceStack()
                .withMaximumPermission(context.server().operatorUserPermissions())
                .withSuppressedOutput();
        for (var command : script.commands()) {
            ParseResults<CommandSourceStack> parsed = context.server().getCommands()
                    .getDispatcher().parse(command.source(), source);
            try {
                Commands.validateParseResults(parsed);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
                throw new ServiceFailure("minecraft_command_parse_failed");
            }
        }
        if (randomTickLease.snapshot().active()) {
            throw new ServiceFailure("random_tick_lease_active");
        }
        int applied = 0;
        try {
            for (var command : script.commands()) {
                AdminBridgeSecurity.Decision current =
                        AdminBridgeSecurity.authorize(context.server());
                if (!current.allowed() || current.context().level() != context.level()
                        || current.context().player() != context.player()) {
                    throw new ServiceFailure(current.allowed()
                            ? "world_context_changed" : current.code());
                }
                context.server().getCommands().performPrefixedCommand(source, command.source());
                applied++;
            }
            randomTickLease.begin(context, script.manifest().randomTickLease());
        } catch (ServiceFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            if (randomTickLease.snapshot().active()) {
                randomTickLease.restore(context.server());
            }
            throw new ServiceFailure("fixture_apply_failed", failure);
        }
        long changed = script.commands().stream()
                .mapToLong(RestrictedCommandPolicy.ValidatedCommand::changedBlocks).sum();
        return new ApplyResult(script.manifest().id(), script.sha256(), session, applied, changed,
                leaseStatus(randomTickLease.snapshot()));
    }

    void onServerTick(IntegratedServer server) {
        AdminBridgeSecurity.Decision decision = AdminBridgeSecurity.authorize(server);
        if (decision.allowed()) {
            randomTickLease.recoverIfPresent(decision.context());
        }
        randomTickLease.onServerTick(server);
    }

    void onServerStopping(IntegratedServer server) {
        randomTickLease.onServerStopping(server);
        if (server == sessionServer) {
            sessionServer = null;
            sessionLevel = null;
            worldSessionId = null;
        }
    }

    private String bindSession(AdminBridgeSecurity.Context context) {
        if (sessionServer != context.server() || sessionLevel != context.level()
                || worldSessionId == null) {
            sessionServer = context.server();
            sessionLevel = context.level();
            worldSessionId = UUID.randomUUID().toString();
        }
        return worldSessionId;
    }

    private <T> T onServer(Function<AdminBridgeSecurity.Context, T> operation)
            throws AdminBridgeException {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !server.isRunning()) {
            throw new AdminBridgeException("integrated_server_unavailable");
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        DispatchGate gate = new DispatchGate();
        try {
            server.execute(() -> {
                synchronized (gate) {
                    if (gate.cancelled) {
                        result.completeExceptionally(
                                new ServiceFailure("server_dispatch_cancelled"));
                        return;
                    }
                    try {
                        AdminBridgeSecurity.Decision decision = AdminBridgeSecurity.authorize(server);
                        if (!decision.allowed()) {
                            throw new ServiceFailure(decision.code());
                        }
                        try {
                            randomTickLease.recoverIfPresent(decision.context());
                        } catch (RandomTickLeaseJournal.JournalException failure) {
                            throw new ServiceFailure(failure.code(), failure);
                        }
                        result.complete(operation.apply(decision.context()));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            throw new AdminBridgeException("server_dispatch_rejected", rejected);
        }
        try {
            return result.get(SERVER_DISPATCH_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            cancelQueuedDispatch(gate, result);
            Thread.currentThread().interrupt();
            throw new AdminBridgeException("server_dispatch_interrupted", interrupted);
        } catch (TimeoutException timeout) {
            if (!cancelQueuedDispatch(gate, result)) {
                return completedResult(result);
            }
            throw new AdminBridgeException("server_dispatch_timeout", timeout);
        } catch (ExecutionException failure) {
            throw translate(failure);
        }
    }

    /** Returns true only when a queued operation was cancelled before entering its mutation fence. */
    private static boolean cancelQueuedDispatch(DispatchGate gate, CompletableFuture<?> result) {
        synchronized (gate) {
            if (result.isDone()) {
                return false;
            }
            gate.cancelled = true;
            return true;
        }
    }

    private static <T> T completedResult(CompletableFuture<T> result) throws AdminBridgeException {
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdminBridgeException("server_dispatch_interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw translate(failure);
        }
    }

    private static AdminBridgeException translate(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof ServiceFailure serviceFailure) {
            return new AdminBridgeException(serviceFailure.code, serviceFailure);
        }
        return new AdminBridgeException("server_dispatch_failed", cause);
    }

    private static LeaseStatus leaseStatus(AdminRandomTickLease.Snapshot snapshot) {
        return new LeaseStatus(snapshot.active(), snapshot.target(), snapshot.remainingSeconds());
    }

    private static final class ServiceFailure extends RuntimeException {
        private final String code;

        private ServiceFailure(String code) {
            this.code = code;
        }

        private ServiceFailure(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }

    private static final class DispatchGate {
        private boolean cancelled;
    }
}
