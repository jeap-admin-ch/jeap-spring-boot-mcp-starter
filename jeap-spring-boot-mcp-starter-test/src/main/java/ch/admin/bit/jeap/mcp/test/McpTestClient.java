package ch.admin.bit.jeap.mcp.test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A real MCP client (MCP Java SDK, streamable HTTP) that authenticates with a bearer token. Use it in tests to
 * exercise tools exactly like an AI client would.
 */
public final class McpTestClient implements AutoCloseable {

    private final McpSyncClient client;

    private McpTestClient(McpSyncClient client) {
        this.client = client;
    }

    /** Connects and runs the MCP initialization handshake. Fails if the server rejects the token. */
    public static McpTestClient connect(String baseUrl, String endpoint, String bearerToken) {
        return connect(baseUrl, endpoint, bearerToken, Map.of());
    }

    /** Like {@link #connect(String, String, String)} with additional headers on every request, e.g. {@code traceparent}. */
    public static McpTestClient connect(String baseUrl, String endpoint, String bearerToken, Map<String, String> headers) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(endpoint)
                .httpRequestCustomizer((request, _, _, _, _) -> {
                    request.header("Authorization", "Bearer " + bearerToken);
                    headers.forEach(request::header);
                })
                .build();
        McpSyncClient sdkClient = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(20))
                .build();
        sdkClient.initialize();
        return new McpTestClient(sdkClient);
    }

    public List<McpSchema.Tool> listTools() {
        return client.listTools().tools();
    }

    public ToolResult call(String tool, Map<String, Object> arguments) {
        return new ToolResult(client.callTool(new McpSchema.CallToolRequest(tool, arguments)));
    }

    public ToolResult call(String tool) {
        return call(tool, Map.of());
    }

    @Override
    public void close() {
        client.closeGracefully();
    }

    /** The outcome of a tool call with convenient access to the text and the structured error code. */
    public record ToolResult(McpSchema.CallToolResult raw) {

        public boolean isError() {
            return Boolean.TRUE.equals(raw.isError());
        }

        public String text() {
            return raw.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .reduce("", String::concat);
        }

        /** The stable error code of a failed call, or {@code null} for a successful call. */
        public String errorCode() {
            return raw.structuredContent() instanceof Map<?, ?> m && m.get("code") != null ? m.get("code").toString() : null;
        }
    }
}
