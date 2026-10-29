package ch.admin.bit.jeap.mcp.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Without {@code jeap.mcp.security.anonymous-access}, a service with no jEAP OAuth2 resource server configured
 * fails to start -- the MCP endpoint could otherwise only ever be reached anonymously, with no way to ever honor
 * a tool's own {@code @PreAuthorize} rule, see {@code McpSecurityAutoConfiguration}. A plain imperative boot
 * (not {@code @SpringBootTest}) since the assertion is on the startup failure itself.
 */
class McpNoResourceServerWithoutAnonymousAccessIT {

    @Test
    void startupFailsWithAClearMessage() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpTestApplication.class)
                .web(WebApplicationType.SERVLET)
                // Command-line-style args, not .properties(...): that sets *default* properties (lowest
                // precedence), which would not override the issuer the shared test application.yml sets.
                .run(
                        "--server.port=0",
                        "--jeap.security.oauth2.resourceserver.authorization-server.issuer="
                ))
                .rootCause()
                .hasMessageContaining("jeap.mcp.security.anonymous-access: true");
    }
}
