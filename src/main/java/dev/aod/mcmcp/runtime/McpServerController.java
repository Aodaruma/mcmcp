package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.McmcpMod;
import dev.aod.mcmcp.mcp.McpHttpServer;
import dev.aod.mcmcp.mcp.McpHttpServerConfig;
import dev.aod.mcmcp.safety.BearerTokenStore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Owns blocking Tomcat lifecycle work outside the Minecraft client thread. */
public final class McpServerController implements AutoCloseable {
    private final McmcpRuntime runtime;
    private final String modVersion;
    private final AtomicReference<McpHttpServer> server = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile CompletableFuture<Void> lifecycle = CompletableFuture.completedFuture(null);

    public McpServerController(McmcpRuntime runtime, String modVersion) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion");
    }

    public synchronized void start(Path gameDirectory) {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            return;
        }
        var configDirectory = gameDirectory.toAbsolutePath().normalize().resolve("config").resolve("mcmcp");
        lifecycle = CompletableFuture.runAsync(() -> startBlocking(configDirectory), command ->
                Thread.ofPlatform().daemon(true).name("mcmcp-mcp-lifecycle").start(command));
    }

    private void startBlocking(Path configDirectory) {
        try {
            var token = new BearerTokenStore().loadOrCreate(configDirectory);
            int port = Integer.getInteger("mcmcp.port", 8_765);
            var config = McpHttpServerConfig.builder(configDirectory.resolve("runtime"), token.secret())
                    .port(port)
                    .serverInfo("mcmcp", modVersion)
                    .build();
            var candidate = new McpHttpServer(config, runtime);
            candidate.start();
            server.set(candidate);
            state.set(State.RUNNING);
            McmcpMod.LOGGER.info(
                    "MCMCP MCP listening on 127.0.0.1:{}; token file: config/mcmcp/mcp-token",
                    candidate.localPort());
        } catch (Exception failure) {
            state.set(State.FAILED);
            McmcpMod.LOGGER.error("MCMCP MCP failed to start; automation endpoint remains disabled", failure);
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
                    McmcpMod.LOGGER.error("MCMCP MCP shutdown failed", failure);
                    state.set(State.FAILED);
                    return;
                }
            }
            state.set(State.STOPPED);
        }, command -> Thread.ofPlatform().daemon(true).name("mcmcp-mcp-shutdown").start(command));
    }

    public void awaitStopped(long timeout, TimeUnit unit) {
        try {
            lifecycle.get(timeout, unit);
        } catch (Exception failure) {
            McmcpMod.LOGGER.warn("Timed out waiting for MCMCP MCP lifecycle", failure);
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
