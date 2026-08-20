package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.CraftAgentMod;
import dev.aodaruma.craftagent.mcp.McpHttpServer;
import dev.aodaruma.craftagent.mcp.McpHttpServerConfig;
import dev.aodaruma.craftagent.safety.BearerTokenStore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns blocking Tomcat lifecycle work outside the Minecraft client thread. */
public final class McpServerController implements AutoCloseable {
    private final CraftAgentRuntime runtime;
    private final String modVersion;
    private final AtomicReference<McpHttpServer> server = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile CompletableFuture<Void> lifecycle = CompletableFuture.completedFuture(null);

    public McpServerController(CraftAgentRuntime runtime, String modVersion) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
    }

    public synchronized void start(Path gameDirectory) {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            return;
        }
        var configDirectory = gameDirectory.toAbsolutePath().normalize().resolve("config").resolve("craftagent");
        lifecycle = CompletableFuture.runAsync(() -> startBlocking(configDirectory), command ->
                Thread.ofPlatform().daemon(true).name("craftagent-mcp-lifecycle").start(command));
    }

    private void startBlocking(Path configDirectory) {
        try {
            var token = new BearerTokenStore().loadOrCreate(configDirectory);
            int port = Integer.getInteger("craftagent.port", 8_765);
            var config = McpHttpServerConfig.builder(configDirectory.resolve("runtime"), token.secret())
                    .port(port)
                    .serverInfo("craftagent", modVersion)
                    .build();
            var candidate = new McpHttpServer(config, runtime);
            candidate.start();
            server.set(candidate);
            state.set(State.RUNNING);
            CraftAgentMod.LOGGER.info(
                    "CraftAgent MCP listening on 127.0.0.1:{}; token file: config/craftagent/bearer.token",
                    candidate.localPort());
        } catch (Exception failure) {
            state.set(State.FAILED);
            CraftAgentMod.LOGGER.error("CraftAgent MCP failed to start; automation endpoint remains disabled", failure);
        }
    }

    public State state() {
        return state.get();
    }

    public int localPort() {
        var current = server.get();
        return current == null || current.state() != McpHttpServer.State.RUNNING ? -1 : current.localPort();
    }

    @Override
    public synchronized void close() {
        var previous = state.getAndSet(State.STOPPING);
        if (previous == State.STOPPED || previous == State.NEW) {
            state.set(State.STOPPED);
            return;
        }
        lifecycle = lifecycle.handle((ignored, startFailure) -> null).thenRunAsync(() -> {
            var current = server.getAndSet(null);
            if (current != null) {
                try {
                    current.close();
                } catch (McpHttpServer.McpHttpServerException failure) {
                    CraftAgentMod.LOGGER.error("CraftAgent MCP shutdown failed", failure);
                    state.set(State.FAILED);
                    return;
                }
            }
            state.set(State.STOPPED);
        }, command -> Thread.ofPlatform().daemon(true).name("craftagent-mcp-shutdown").start(command));
    }

    public void awaitStopped(long timeout, TimeUnit unit) {
        try {
            lifecycle.get(timeout, unit);
        } catch (Exception failure) {
            CraftAgentMod.LOGGER.warn("Timed out waiting for CraftAgent MCP lifecycle", failure);
        }
    }

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }
}
