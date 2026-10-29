package ch.admin.bit.jeap.mcp;

import java.time.Duration;

/**
 * Immutable summary of a completed tool call. Contains neither parameters nor results nor tokens.
 *
 * @param tool            published tool name
 * @param successful      false if the call was answered with an error
 * @param errorCode       the error code if the call failed, otherwise null
 * @param duration        time from the start of the call until the response was determined
 * @param resultSizeBytes serialized size of the result, 0 if the call failed before producing one
 * @param principal       name of the authenticated caller as determined by the security starter, or null
 */
public record McpInvocation(String tool, boolean successful, String errorCode, Duration duration,
                            long resultSizeBytes, String principal) {
}
