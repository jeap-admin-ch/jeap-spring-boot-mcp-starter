package ch.admin.bit.jeap.mcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Technical operating limits and discovery settings of the MCP server. Authorization is not configured here: access
 * rules are declared with {@code @PreAuthorize} on the tools.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("jeap.mcp")
public class McpProperties {

    @Valid
    @NestedConfigurationProperty
    private Security security = new Security();

    @Valid
    @NestedConfigurationProperty
    private Execution execution = new Execution();

    @Valid
    @NestedConfigurationProperty
    private Limits limits = new Limits();

    /**
     * Overrides of the global defaults per tool, keyed by the published tool name.
     */
    private Map<String, @Valid ToolProperties> tools = new HashMap<>();

    @Getter
    @Setter
    public static class Security {
        /**
         * Canonical external URL of the MCP endpoint (for example https://my-service.example.ch/mcp), published as
         * resource in the OAuth protected resource metadata. Never derived from request headers.
         */
        private String resourceUri;

        /**
         * Allows requests without a bearer token to reach the MCP endpoint, as a deliberate opt-in. A present but
         * invalid or expired token is still rejected with 401 if a jEAP OAuth2 resource server is configured at
         * all; if it is not, this must be {@code true}, since there is then no way to ever be anything but
         * anonymous, and the startup fails otherwise. Tools stay protected individually: a public tool still
         * needs an explicit {@code @PreAuthorize("permitAll()")}, every other tool keeps denying anonymous
         * callers (permanently, if no resource server is configured). Off by default.
         */
        private boolean anonymousAccess = false;
    }

    @Getter
    @Setter
    public static class Execution {
        /**
         * Default execution deadline of a tool call.
         */
        @NotNull
        private Duration timeout = Duration.ofSeconds(15);

        /**
         * Deadline for a single {@code McpInvocationObserver} notification after a call has completed. Observer
         * notification runs synchronously, after the tool's own deadline window has already closed, so without a
         * bound of its own a slow or hanging observer would add unbounded latency to every tool call. A timed-out
         * observer is only logged; it never changes the tool result.
         */
        @NotNull
        private Duration observerTimeout = Duration.ofSeconds(1);
    }

    @Getter
    @Setter
    public static class Limits {
        /**
         * Maximum size of a request body.
         */
        @NotNull
        private DataSize maxRequestSize = DataSize.ofKilobytes(256);

        /**
         * Maximum serialized size of a tool result.
         */
        @NotNull
        private DataSize maxResponseSize = DataSize.ofMegabytes(1);

        /**
         * Maximum number of concurrently executing tool calls per instance.
         */
        @Positive
        private int maxConcurrentCalls = 20;
    }

    @Getter
    @Setter
    public static class ToolProperties {
        private Duration timeout;
        private DataSize maxResponseSize;
        @Positive
        private Integer maxConcurrentCalls;
    }
}
