package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.DispatcherType;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Lifecycle owner for the loopback-only, stateless Streamable HTTP endpoint. */
public final class McpHttpServer implements AutoCloseable {
    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    private static final String SERVLET_NAME = "craftagent-mcp";
    private static final String FILTER_NAME = "craftagent-mcp-guard";
    private static final String ENDPOINT = "/mcp";

    private final McpHttpServerConfig config;
    private final McpRuntimePort runtimePort;

    private volatile State state = State.NEW;
    private volatile int localPort = -1;
    private Tomcat tomcat;
    private McpStatelessSyncServer mcpServer;

    public McpHttpServer(McpHttpServerConfig config, McpRuntimePort runtimePort) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtimePort = Objects.requireNonNull(runtimePort, "runtimePort");
    }

    /**
     * Starts Tomcat synchronously. Call from a mod lifecycle worker, never the client/render thread.
     */
    public synchronized void start() throws McpHttpServerException {
        if (state != State.NEW) {
            throw new IllegalStateException("MCP server can only be started once; current state=" + state);
        }
        state = State.STARTING;
        try {
            Path tomcatBase = config.baseDirectory().resolve("tomcat");
            Path webRoot = config.baseDirectory().resolve("webroot");
            Files.createDirectories(tomcatBase);
            Files.createDirectories(webRoot);

            HttpServletStatelessServerTransport servletTransport =
                    HttpServletStatelessServerTransport.builder()
                            .messageEndpoint(ENDPOINT)
                            .securityValidator(DefaultServerTransportSecurityValidator.builder()
                                    .allowedHosts(config.allowedHosts())
                                    .allowedOrigins(config.allowedOrigins())
                                    .build())
                            .build();
            ProtocolPinnedStatelessTransport pinnedTransport =
                    new ProtocolPinnedStatelessTransport(servletTransport);
            CraftAgentToolRegistry registry =
                    new CraftAgentToolRegistry(runtimePort, config.runtimeDispatchTimeout());
            mcpServer = McpServer.sync(pinnedTransport)
                    .serverInfo(config.serverName(), config.serverVersion())
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .strictToolNameValidation(true)
                    // Validate inside CraftAgentToolRegistry so every schema failure uses the
                    // same bounded, sanitized structured error envelope and never reaches SDK logging.
                    .validateToolInputs(false)
                    .immediateExecution(true)
                    .tools(registry.specifications())
                    .build();

            Tomcat server = new Tomcat();
            tomcat = server;
            server.setBaseDir(tomcatBase.toString());
            server.setHostname("127.0.0.1");
            server.setSilent(true);

            Connector connector = new Connector();
            connector.setPort(config.port());
            connector.setProperty("address", "127.0.0.1");
            connector.setProperty("connectionTimeout", "5000");
            connector.setProperty("keepAliveTimeout", "5000");
            connector.setProperty("maxKeepAliveRequests", "100");
            connector.setProperty("maxThreads", Integer.toString(Math.max(8, config.maxConcurrentRequests() + 4)));
            connector.setProperty("acceptCount", "8");
            connector.setProperty("maxConnections", "32");
            connector.setProperty("maxHttpRequestHeaderSize", "16384");
            connector.setProperty("maxSwallowSize", Integer.toString(config.maxRequestBodyBytes()));
            server.setConnector(connector);

            Context context = server.addContext("", webRoot.toString());
            context.setParentClassLoader(McpHttpServer.class.getClassLoader());
            Wrapper wrapper = Tomcat.addServlet(context, SERVLET_NAME, servletTransport);
            wrapper.setLoadOnStartup(1);
            wrapper.setAsyncSupported(true);
            context.addServletMappingDecoded(ENDPOINT, SERVLET_NAME);

            FilterDef filterDefinition = new FilterDef();
            filterDefinition.setFilterName(FILTER_NAME);
            filterDefinition.setFilter(new McpRequestGuardFilter(config));
            filterDefinition.setAsyncSupported("true");
            context.addFilterDef(filterDefinition);

            FilterMap filterMapping = new FilterMap();
            filterMapping.setFilterName(FILTER_NAME);
            filterMapping.addURLPattern(ENDPOINT);
            filterMapping.setDispatcher(DispatcherType.REQUEST.name());
            context.addFilterMapBefore(filterMapping);

            server.start();
            localPort = connector.getLocalPort();
            if (localPort < 1) {
                throw new LifecycleException("Tomcat did not expose a bound local port");
            }
            state = State.RUNNING;
        }
        catch (IOException | LifecycleException | RuntimeException exception) {
            state = State.FAILED;
            Throwable cleanupFailure = cleanup();
            if (cleanupFailure != null) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new McpHttpServerException("Failed to start loopback MCP server", exception);
        }
    }

    public State state() {
        return state;
    }

    /** Actual bound loopback port, useful when configuration requested ephemeral port {@code 0}. */
    public int localPort() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("MCP server is not running");
        }
        return localPort;
    }

    @Override
    public synchronized void close() throws McpHttpServerException {
        if (state == State.STOPPED || state == State.NEW) {
            state = State.STOPPED;
            return;
        }
        if (state == State.STOPPING) {
            return;
        }
        state = State.STOPPING;
        Throwable failure = cleanup();
        localPort = -1;
        state = failure == null ? State.STOPPED : State.FAILED;
        if (failure != null) {
            throw new McpHttpServerException("Failed to stop loopback MCP server cleanly", failure);
        }
    }

    private Throwable cleanup() {
        Throwable failure = null;
        McpStatelessSyncServer sdkServer = mcpServer;
        mcpServer = null;
        if (sdkServer != null) {
            try {
                sdkServer.closeGracefully();
            }
            catch (RuntimeException exception) {
                failure = exception;
            }
        }

        Tomcat embedded = tomcat;
        tomcat = null;
        if (embedded != null) {
            try {
                embedded.stop();
            }
            catch (LifecycleException exception) {
                failure = appendFailure(failure, exception);
            }
            try {
                embedded.destroy();
            }
            catch (LifecycleException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        return failure;
    }

    private static Throwable appendFailure(Throwable current, Throwable additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }

    public static final class McpHttpServerException extends Exception {
        private static final long serialVersionUID = 1L;

        McpHttpServerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
