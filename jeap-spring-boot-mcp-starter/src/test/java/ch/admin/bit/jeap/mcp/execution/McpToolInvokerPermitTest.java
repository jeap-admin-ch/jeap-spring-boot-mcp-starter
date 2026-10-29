package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.config.McpProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolInvokerPermitTest {

    private static final CallToolRequest REQUEST = new CallToolRequest("tool", Map.of());

    @Test
    void permitsAreReleasedWhenTheCallIsCancelledBeforeOrWhileTheToolStarts() throws Exception {
        McpProperties properties = new McpProperties();
        properties.getLimits().setMaxConcurrentCalls(1);
        properties.getTools().put("tool", new McpProperties.ToolProperties());
        properties.getTools().get("tool").setMaxConcurrentCalls(1);
        try (McpToolInvoker invoker = new McpToolInvoker(properties, new SimpleMeterRegistry(), ObservationRegistry.NOOP, List.of())) {
            var handler = invoker.wrap(toolSpec()).callHandler();

            for (int i = 0; i < 200; i++) {
                // An interrupted caller makes future.get() fail immediately, so the task is cancelled right after
                // submit: either before it started (the case that used to leak) or while it starts.
                Thread.currentThread().interrupt();
                handler.apply(null, REQUEST);
                Thread.interrupted();

                assertThat(callSucceedsEventually(handler)).as("call %d", i).isTrue();
            }
        }
    }

    private static boolean callSucceedsEventually(java.util.function.BiFunction<io.modelcontextprotocol.common.McpTransportContext, CallToolRequest, CallToolResult> handler)
            throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!Boolean.TRUE.equals(handler.apply(null, REQUEST).isError())) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    private static SyncToolSpecification toolSpec() {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder("tool").description("tool").build())
                .callHandler((context, request) -> CallToolResult.builder().build())
                .build();
    }
}
