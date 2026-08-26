package dev.aodaruma.craftagent.mcp;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolPinnedStatelessTransportTest {
    @Test
    void advertisesOnlyThePinnedProtocolAndDelegatesLifecycle() {
        RecordingTransport delegate = new RecordingTransport();
        ProtocolPinnedStatelessTransport transport = new ProtocolPinnedStatelessTransport(delegate);

        transport.setMcpHandler(null);
        transport.closeGracefully().block();
        transport.close();

        assertThat(transport.protocolVersions()).containsExactly(ProtocolVersions.MCP_2025_11_25);
        assertThat(delegate.handlerSet).isTrue();
        assertThat(delegate.gracefulClosed).isTrue();
        assertThat(delegate.closed).isTrue();
    }

    private static final class RecordingTransport implements McpStatelessServerTransport {
        private boolean handlerSet;
        private boolean gracefulClosed;
        private boolean closed;

        @Override
        public void setMcpHandler(McpStatelessServerHandler handler) {
            handlerSet = true;
        }

        @Override
        public Mono<Void> closeGracefully() {
            gracefulClosed = true;
            return Mono.empty();
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public List<String> protocolVersions() {
            return List.of("unexpected");
        }
    }
}
