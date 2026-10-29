package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.config.McpProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties.ApiType;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties.ServerProtocol;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Plain unit tests for the {@code mcpToolOverrideValidator} and {@code mcpServerProfileValidator} beans: no
 * Spring context needed, calling the {@code @Bean} factory method directly already gives the real
 * {@code SmartInitializingSingleton}.
 */
class McpExecutionAutoConfigurationTest {

    private final McpExecutionAutoConfiguration autoConfiguration = new McpExecutionAutoConfiguration();

    @Test
    void unknownToolNameInPerToolConfigFailsStartup() {
        McpProperties properties = new McpProperties();
        properties.getTools().put("does-not-exist", new McpProperties.ToolProperties());
        McpToolInvoker invoker = newInvoker(properties);
        invoker.wrap(toolSpec("real_tool"));

        assertThatIllegalStateException()
                .isThrownBy(() -> autoConfiguration.mcpToolOverrideValidator(properties, invoker).afterSingletonsInstantiated())
                .withMessageContaining("does-not-exist")
                .withMessageContaining("real_tool");
    }

    @Test
    void perToolConfigForAPublishedToolIsAccepted() {
        McpProperties properties = new McpProperties();
        properties.getTools().put("real_tool", new McpProperties.ToolProperties());
        McpToolInvoker invoker = newInvoker(properties);
        invoker.wrap(toolSpec("real_tool"));

        assertThatNoException()
                .isThrownBy(() -> autoConfiguration.mcpToolOverrideValidator(properties, invoker).afterSingletonsInstantiated());
    }

    @Test
    void syncStatelessServerProfileIsAccepted() {
        McpServerProperties serverProperties = new McpServerProperties();
        serverProperties.setType(ApiType.SYNC);
        serverProperties.setProtocol(ServerProtocol.STATELESS);

        assertThatNoException()
                .isThrownBy(() -> autoConfiguration.mcpServerProfileValidator(serverProperties).afterSingletonsInstantiated());
    }

    @Test
    void defaultStreamableProtocolFailsStartup() {
        // Spring AI's own default (ServerProtocol.STREAMABLE), not STATELESS: this starter's execution
        // limits, error contract and authorization checks are only implemented and verified for STATELESS.
        McpServerProperties serverProperties = new McpServerProperties();

        assertThatIllegalStateException()
                .isThrownBy(() -> autoConfiguration.mcpServerProfileValidator(serverProperties).afterSingletonsInstantiated())
                .withMessageContaining("SYNC").withMessageContaining("STATELESS").withMessageContaining("STREAMABLE");
    }

    @Test
    void asyncApiTypeFailsStartupEvenWithTheStatelessProtocol() {
        McpServerProperties serverProperties = new McpServerProperties();
        serverProperties.setType(ApiType.ASYNC);
        serverProperties.setProtocol(ServerProtocol.STATELESS);

        assertThatIllegalStateException()
                .isThrownBy(() -> autoConfiguration.mcpServerProfileValidator(serverProperties).afterSingletonsInstantiated())
                .withMessageContaining("ASYNC");
    }

    private static McpToolInvoker newInvoker(McpProperties properties) {
        return new McpToolInvoker(properties, new SimpleMeterRegistry(), ObservationRegistry.NOOP, List.of());
    }

    private static SyncToolSpecification toolSpec(String name) {
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name).description(name).build())
                .callHandler((context, request) -> null)
                .build();
    }
}
