package ch.admin.bit.jeap.mcp.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

// Deliberately no @EnableMethodSecurity here: it must come from the starter itself (McpSecurityAutoConfiguration's
// own fallback when no jEAP OAuth2 resource server is configured, or the jEAP security starter's own
// autoconfiguration once one is), exactly as a consuming service would get it. Adding it here would mask either
// one silently failing to do so.
@SpringBootApplication
public class McpTestApplication {

    @org.springframework.context.annotation.Bean
    io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    // Publishes AiToolTestTools' @Tool method as a ToolCallback; Spring AI's StatelessToolCallbackConverterAutoConfiguration
    // converts it into the same SyncToolSpecification shape an @McpTool method would produce.
    @org.springframework.context.annotation.Bean
    org.springframework.ai.tool.ToolCallbackProvider aiToolCallbackProvider(AiToolTestTools tools) {
        return org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
