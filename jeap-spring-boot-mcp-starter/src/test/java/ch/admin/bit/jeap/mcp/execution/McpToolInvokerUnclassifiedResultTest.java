package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.config.McpProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delegate returning an {@code isError: true} result directly, with no {@link McpInvocationScope} failure
 * recorded, is exactly what reaches the invoker when a real tool method is never invoked at all (Spring AI's
 * own argument/JSON conversion failing before the call) or when it returns an error result directly instead
 * of throwing {@link ch.admin.bit.jeap.mcp.McpToolException} -- {@code McpToolErrorInterceptor} classifies
 * every exception a tool method itself throws, with no uncaught case, so there is no other way to reach this.
 * Exercised through a hand-built delegate, like {@link McpToolInvokerPermitTest}, since reliably triggering
 * either real case through the full Spring AI annotation/schema-validation stack is not deterministic enough
 * for a unit test (a malformed argument is actually rejected one layer further out, by the MCP SDK's own
 * input schema validation, before even reaching this delegate).
 */
class McpToolInvokerUnclassifiedResultTest {

    private static final CallToolRequest REQUEST = new CallToolRequest("tool", Map.of());

    @Test
    void unclassifiedErrorResultBecomesInvalidArgumentsNotAnOpaqueInternalError() {
        McpProperties properties = new McpProperties();
        try (McpToolInvoker invoker = new McpToolInvoker(properties, new SimpleMeterRegistry(), ObservationRegistry.NOOP, List.of())) {
            var handler = invoker.wrap(SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("tool").description("tool").build())
                    .callHandler((context, request) -> CallToolResult.builder()
                            .isError(true)
                            .content(List.of(new TextContent("some raw detail from Spring AI, not ours to expose")))
                            .build())
                    .build()).callHandler();

            CallToolResult result = handler.apply(null, REQUEST);

            assertThat(result.isError()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> structuredContent = (Map<String, Object>) result.structuredContent();
            assertThat(structuredContent).containsEntry("code", "INVALID_ARGUMENTS");
            assertThat(result.content()).extracting(c -> ((TextContent) c).text())
                    .noneMatch(text -> text.contains("some raw detail from Spring AI"));
        }
    }
}
