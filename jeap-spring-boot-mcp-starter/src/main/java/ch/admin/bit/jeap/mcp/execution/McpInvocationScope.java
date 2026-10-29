package ch.admin.bit.jeap.mcp.execution;

/**
 * Per-call holder that links the exception raised inside a tool method (which the Spring AI tool callback turns into
 * a plain error text) to the invocation that is managed by the {@link McpToolInvoker}.
 */
final class McpInvocationScope {

    record Failure(String code, String message) {
    }

    private static final ThreadLocal<McpInvocationScope> CURRENT = new ThreadLocal<>();

    private volatile Failure failure;

    static McpInvocationScope open() {
        McpInvocationScope scope = new McpInvocationScope();
        CURRENT.set(scope);
        return scope;
    }

    static void close() {
        CURRENT.remove();
    }

    static McpInvocationScope current() {
        return CURRENT.get();
    }

    void fail(Failure failure) {
        this.failure = failure;
    }

    Failure failure() {
        return failure;
    }
}
