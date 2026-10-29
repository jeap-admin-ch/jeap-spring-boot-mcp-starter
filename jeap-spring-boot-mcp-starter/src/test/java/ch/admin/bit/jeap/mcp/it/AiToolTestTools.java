package ch.admin.bit.jeap.mcp.it;

import ch.admin.bit.jeap.mcp.McpToolException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Tools published through Spring AI's native {@code @Tool} annotation (not {@code @McpTool}), via a
 * {@code ToolCallbackProvider} bean (see {@link McpTestApplication}), to check that execution protections and
 * the error contract apply to them exactly like to an {@code @McpTool} method -- not just that the startup
 * validator requires {@code @PreAuthorize} on them (that part is already covered by
 * {@code McpToolAuthorizationValidatorTest}).
 */
@Component
public class AiToolTestTools {

    @Tool(name = "ai_protected_echo", description = "Echoes text via Spring AI's native @Tool annotation, requires role READER")
    @PreAuthorize("hasRole('READER')")
    public String aiProtectedEcho(@ToolParam(description = "Text") String text) {
        return text;
    }

    @Tool(name = "ai_admin_only", description = "Requires role ADMIN, which the test caller never has")
    @PreAuthorize("hasRole('ADMIN')")
    public String aiAdminOnly() {
        return "admin-only";
    }

    @Tool(name = "ai_fail", description = "Fails with an internal exception")
    @PreAuthorize("hasRole('READER')")
    public String aiFail() {
        throw new IllegalStateException("secret internal detail");
    }

    @Tool(name = "ai_business", description = "Fails with a business error")
    @PreAuthorize("hasRole('READER')")
    public String aiBusiness() {
        throw new McpToolException("SYSTEM_NOT_FOUND", "The system does not exist");
    }
}
