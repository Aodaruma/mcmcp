package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/** Restricts SDK protocol negotiation to the version implemented and tested by this mod. */
final class ProtocolPinnedStatelessTransport implements McpStatelessServerTransport {
    private static final List<String> SUPPORTED_PROTOCOLS = List.of(ProtocolVersions.MCP_2025_11_25);

    private final McpStatelessServerTransport delegate;

    ProtocolPinnedStatelessTransport(McpStatelessServerTransport delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        delegate.setMcpHandler(handler);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return SUPPORTED_PROTOCOLS;
    }
}
