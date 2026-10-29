package ch.admin.bit.jeap.mcp;

/**
 * Stable technical error codes returned in the {@code code} field of the structured content of a failed tool call.
 * Business errors use their own codes, see {@link McpToolException}.
 */
public enum McpErrorCode {
    /** The caller is not allowed to execute the tool or to access the requested data. */
    FORBIDDEN,
    /** The execution deadline was exceeded. A write operation may still have been applied. */
    TIMEOUT,
    /** Too many concurrent calls; the call was rejected without being executed and can be retried later. */
    CAPACITY_EXCEEDED,
    /** The result exceeds the configured maximum size and was not returned. */
    RESULT_TOO_LARGE,
    /**
     * The call never reached the tool method: Spring AI could not convert the supplied arguments to the
     * method's parameter types, or the method returned an error-shaped result directly instead of throwing
     * {@link McpToolException} (the only supported way to report a business error -- see {@link McpToolException}).
     * The message is deliberately generic; neither case is classified further.
     */
    INVALID_ARGUMENTS,
    /** Unexpected technical error. Details are only available in the server log, using the correlation id. */
    INTERNAL_ERROR
}
