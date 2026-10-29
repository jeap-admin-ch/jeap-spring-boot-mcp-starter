package ch.admin.bit.jeap.mcp;

import lombok.Getter;

/**
 * To be thrown by a tool to report a business error to the MCP client. Both the code and the message are returned
 * to the client as they are, so they must be stable, safe to expose and must not contain internal details.
 */
@Getter
public class McpToolException extends RuntimeException {

    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }
}
