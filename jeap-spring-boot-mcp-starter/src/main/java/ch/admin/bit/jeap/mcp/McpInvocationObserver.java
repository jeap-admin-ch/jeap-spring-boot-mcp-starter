package ch.admin.bit.jeap.mcp;

/**
 * Optional extension point notified once after every tool call. Failures of an observer are logged and never change
 * the result of the call. It is not an audit trail: there is no delivery guarantee.
 */
@FunctionalInterface
public interface McpInvocationObserver {

    void onInvocationCompleted(McpInvocation invocation);
}
