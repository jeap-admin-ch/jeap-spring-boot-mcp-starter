package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.McpErrorCode;
import ch.admin.bit.jeap.mcp.McpToolException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;

/**
 * Outermost advice around {@code @McpTool} methods (outside of method security). It records a classified, safe
 * failure for the running tool call, because the Spring AI callback swallows the exception and would otherwise
 * return the raw exception text to the client. Outside of a managed tool call the exception is passed on unchanged.
 */
final class McpToolErrorInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(McpToolErrorInterceptor.class);

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            McpInvocationScope scope = McpInvocationScope.current();
            if (scope != null) {
                scope.fail(classify(invocation, t));
            }
            throw t;
        }
    }

    private static McpInvocationScope.Failure classify(MethodInvocation invocation, Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof AccessDeniedException) {
                return new McpInvocationScope.Failure(McpErrorCode.FORBIDDEN.name(), "Access denied");
            }
            if (cause instanceof InterruptedException) {
                log.debug("MCP tool method {} was interrupted", invocation.getMethod().getName());
                return new McpInvocationScope.Failure(McpErrorCode.INTERNAL_ERROR.name(), "The tool call was cancelled");
            }
            if (cause instanceof McpToolException businessException) {
                return new McpInvocationScope.Failure(businessException.getCode(), businessException.getMessage());
            }
        }
        String correlationId = UUID.randomUUID().toString();
        log.error("MCP tool method {} failed unexpectedly (correlationId={})", invocation.getMethod().getName(), correlationId, t);
        return new McpInvocationScope.Failure(McpErrorCode.INTERNAL_ERROR.name(), "Internal error (correlationId=" + correlationId + ")");
    }
}
