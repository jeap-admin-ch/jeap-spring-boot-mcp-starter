package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.McpErrorCode;
import ch.admin.bit.jeap.mcp.McpInvocation;
import ch.admin.bit.jeap.mcp.McpInvocationObserver;
import ch.admin.bit.jeap.mcp.config.McpProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.MDC;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Executes tool calls with the protection of the MCP starter: concurrency limits per instance (global and per tool),
 * an execution deadline, a result size limit, safe error mapping, metrics, logging and observer notification.
 * Calls are rejected immediately if no permit is available; a permit is only released when the tool method has
 * actually terminated, also after a timeout.
 */
public class McpToolInvoker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolInvoker.class);

    private final McpProperties properties;
    private final MeterRegistry meterRegistry;
    private final List<McpInvocationObserver> observers;
    private final Semaphore globalPermits;
    private final Map<String, Semaphore> toolPermits = new ConcurrentHashMap<>();
    private final Set<String> knownTools = ConcurrentHashMap.newKeySet();
    private final ObservationRegistry observationRegistry;
    // Carries the security context and, via context propagation, the current observation (and with it the trace)
    // to the virtual thread the tool runs on.
    private final ExecutorService executor = ContextExecutorService.wrap(
            new DelegatingSecurityContextExecutorService(
                    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mcp-tool-", 0).factory())));

    public McpToolInvoker(McpProperties properties, MeterRegistry meterRegistry, ObservationRegistry observationRegistry,
                          List<McpInvocationObserver> observers) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
        this.observers = List.copyOf(observers);
        this.globalPermits = new Semaphore(properties.getLimits().getMaxConcurrentCalls());
    }

    /**
     * Names of all tools seen so far, used to validate the per-tool configuration.
     */
    public Set<String> knownTools() {
        return Set.copyOf(knownTools);
    }

    public SyncToolSpecification wrap(SyncToolSpecification specification) {
        String tool = specification.tool().name();
        knownTools.add(tool);
        McpProperties.ToolProperties toolProperties = properties.getTools().get(tool);
        if (toolProperties != null && toolProperties.getMaxConcurrentCalls() != null) {
            toolPermits.put(tool, new Semaphore(toolProperties.getMaxConcurrentCalls()));
        }
        BiFunction<McpTransportContext, CallToolRequest, CallToolResult> delegate = specification.callHandler();
        return SyncToolSpecification.builder()
                .tool(specification.tool())
                .callHandler((context, request) -> invoke(tool, delegate, context, request))
                .build();
    }

    private CallToolResult invoke(String tool, BiFunction<McpTransportContext, CallToolRequest, CallToolResult> delegate,
                                  McpTransportContext context, CallToolRequest request) {
        long start = System.nanoTime();
        String principal = principal();
        Semaphore toolSemaphore = toolPermits.get(tool);

        CallToolResult result;
        if (!globalPermits.tryAcquire()) {
            result = error(McpErrorCode.CAPACITY_EXCEEDED.name(), "Too many concurrent calls, try again later");
        } else if (toolSemaphore != null && !toolSemaphore.tryAcquire()) {
            globalPermits.release();
            result = error(McpErrorCode.CAPACITY_EXCEEDED.name(), "Too many concurrent calls to this tool, try again later");
        } else {
            result = execute(tool, delegate, context, request, toolSemaphore);
        }
        return complete(tool, result, Duration.ofNanos(System.nanoTime() - start), principal);
    }

    /**
     * Runs the tool in its own observation, a child of the observation of the HTTP request. With a tracer on the
     * classpath this yields a span for the tool call, and everything the tool does is part of the caller's trace.
     * <p>
     * A classified failure (recorded in {@code scope}, because Spring AI's own callback already swallowed the
     * exception by now) is reported two ways: {@code observation.error(...)} so Micrometer's own {@code error} tag
     * and span status reflect the failure, and a separate {@code mcp.error.code} tag carrying the actual code --
     * not the plain key {@code error}, which {@code DefaultMeterObservationHandler} manages itself and would
     * otherwise silently overwrite with {@code none} on every call where {@code observation.error(...)} was not
     * called with that exact mechanism.
     */
    private CallToolResult observed(String tool, McpInvocationScope scope, Supplier<CallToolResult> call) {
        Observation observation = Observation.createNotStarted("jeap.mcp.tool", observationRegistry)
                .lowCardinalityKeyValue("tool", tool)
                .start();
        try (Observation.Scope _ = observation.openScope()) {
            CallToolResult result = call.get();
            if (scope.failure() != null) {
                String code = safeErrorCode(scope.failure().code());
                observation.lowCardinalityKeyValue("mcp.error.code", code);
                observation.error(new ClassifiedFailure(code));
            }
            return result;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    private CallToolResult execute(String tool, BiFunction<McpTransportContext, CallToolRequest, CallToolResult> delegate,
                                   McpTransportContext context, CallToolRequest request, Semaphore toolSemaphore) {
        McpInvocationScope[] scopeHolder = new McpInvocationScope[1];
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        // Whoever flips this first owns the permits: the task once it starts, or the caller if it cancels before.
        AtomicBoolean started = new AtomicBoolean();
        Runnable releasePermits = () -> {
            if (toolSemaphore != null) {
                toolSemaphore.release();
            }
            globalPermits.release();
        };
        Callable<CallToolResult> task = () -> {
            if (!started.compareAndSet(false, true)) {
                return null;
            }
            try {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                scopeHolder[0] = McpInvocationScope.open();
                return observed(tool, scopeHolder[0], () -> delegate.apply(context, request));
            } finally {
                McpInvocationScope.close();
                MDC.clear();
                releasePermits.run();
            }
        };

        Future<CallToolResult> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            releasePermits.run();
            return error(McpErrorCode.CAPACITY_EXCEEDED.name(), "The server is shutting down");
        }

        try {
            CallToolResult raw = future.get(timeout(tool).toMillis(), TimeUnit.MILLISECONDS);
            McpInvocationScope scope = scopeHolder[0];
            if (scope != null && scope.failure() != null) {
                return error(scope.failure().code(), scope.failure().message());
            }
            if (Boolean.TRUE.equals(raw.isError())) {
                // Reached here with no classified failure recorded in the scope: McpToolErrorInterceptor
                // classifies every exception a tool method throws (see its own catch-all), with no uncaught
                // case, so the only way to still get here is that the method was never actually invoked --
                // Spring AI's own argument/JSON conversion failing before the call -- or the method returned
                // an error-shaped result directly instead of throwing McpToolException (the only supported
                // way to report a business error, see docs/execution.md). Either way, don't let whatever raw
                // text it produced leak to the client unfiltered -- defence in depth alongside the
                // interceptor, not a replacement for it -- but a dedicated, genuinely useful code beats a
                // blanket INTERNAL_ERROR for what is, in practice, almost always the caller's own bad input.
                return invalidArguments(tool, raw);
            }
            return raw;
        } catch (TimeoutException e) {
            future.cancel(true);
            if (started.compareAndSet(false, true)) {
                releasePermits.run();
            }
            return error(McpErrorCode.TIMEOUT.name(), "The tool call exceeded its execution deadline");
        } catch (InterruptedException e) {
            future.cancel(true);
            if (started.compareAndSet(false, true)) {
                releasePermits.run();
            }
            Thread.currentThread().interrupt();
            return error(McpErrorCode.INTERNAL_ERROR.name(), "The tool call was interrupted");
        } catch (ExecutionException e) {
            // Reached for whatever Spring AI's own tool callback does not catch and convert to a CallToolResult
            // itself: checked exceptions (wrapped as UndeclaredThrowableException), Errors, and a deliberate
            // McpError. The interceptor still ran and classified the failure into the scope before rethrowing,
            // so prefer that over inventing a second, uncorrelated error here -- except for McpError, which is
            // not a tool error at all: rethrowing it unwrapped lets the transport map it to the genuine
            // JSON-RPC error it carries (confirmed in the MCP SDK: a SyncToolSpecification's callHandler is
            // wrapped in Mono.fromCallable, which turns a thrown exception into a Mono error signal rather than
            // swallowing it, and the SDK special-cases McpError elsewhere for exactly this reason).
            Throwable cause = e.getCause();
            if (findMcpError(cause) instanceof McpError mcpError) {
                throw mcpError;
            }
            McpInvocationScope scope = scopeHolder[0];
            if (scope != null && scope.failure() != null) {
                return error(scope.failure().code(), scope.failure().message());
            }
            String correlationId = UUID.randomUUID().toString();
            log.error("MCP tool call to {} failed unexpectedly (correlationId={})", tool, correlationId, cause);
            return error(McpErrorCode.INTERNAL_ERROR.name(), "Internal error (correlationId=" + correlationId + ")");
        }
    }

    private static McpError findMcpError(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof McpError mcpError) {
                return mcpError;
            }
        }
        return null;
    }

    private CallToolResult invalidArguments(String tool, CallToolResult raw) {
        // Not an unexpected failure (no correlation id needed, nothing is hidden from the client that would
        // need log-correlation to investigate) -- the raw text is only logged, at debug level, since Spring
        // AI's own wording is not guaranteed stable enough to return to the caller as-is.
        log.debug("MCP tool call to {} did not reach the tool method, or it returned an error result directly: {}",
                tool, rawText(raw));
        return error(McpErrorCode.INVALID_ARGUMENTS.name(), "The call could not be processed; check the arguments");
    }

    private static String rawText(CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .findFirst()
                .orElse("(no text)");
    }

    private CallToolResult complete(String tool, CallToolResult result, Duration duration, String principal) {
        long size = 0;
        if (!Boolean.TRUE.equals(result.isError())) {
            size = serializedSize(result);
            long limit = maxResponseSize(tool);
            if (size > limit) {
                log.warn("MCP tool call to {} rejected: result of {} bytes exceeds the limit of {} bytes", tool, size, limit);
                result = error(McpErrorCode.RESULT_TOO_LARGE.name(), "The result exceeds the maximum size and was not returned");
                size = 0;
            }
        }
        String errorCode = errorCode(result);
        boolean successful = errorCode == null;
        record(tool, successful, errorCode, duration, size);
        log.info("MCP tool call tool={} status={} error={} durationMs={} resultBytes={}",
                tool, successful ? "success" : "error", errorCode, duration.toMillis(), size);
        notifyObservers(new McpInvocation(tool, successful, errorCode, duration, size, principal));
        return result;
    }

    private void record(String tool, boolean successful, String errorCode, Duration duration, long size) {
        Timer.builder("jeap.mcp.tool.calls")
                .description("Duration of MCP tool calls")
                .tag("tool", tool)
                .tag("status", successful ? "success" : "error")
                .tag("error", successful ? "none" : errorCode)
                .register(meterRegistry)
                .record(duration);
        if (successful) {
            DistributionSummary.builder("jeap.mcp.tool.result.size")
                    .description("Serialized size of MCP tool results")
                    .baseUnit("bytes")
                    .tag("tool", tool)
                    .register(meterRegistry)
                    .record(size);
        }
    }

    /**
     * Notifies every observer, synchronously: the result is only returned once every observer has either
     * completed or hit its own deadline ({@code jeap.mcp.execution.observer-timeout}). Observers run concurrently
     * with each other (each on its own virtual thread, like a tool call), not sequentially, so with several
     * observers the added latency is bounded by the slowest one, not their sum. A failing or overrunning observer
     * is only logged; it never changes the tool result or stops another observer from running.
     */
    private void notifyObservers(McpInvocation invocation) {
        if (observers.isEmpty()) {
            return;
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<Future<?>> futures = new ArrayList<>(observers.size());
        for (McpInvocationObserver observer : observers) {
            futures.add(submitObserver(observer, invocation, mdc));
        }
        Duration observerTimeout = properties.getExecution().getObserverTimeout();
        for (int i = 0; i < observers.size(); i++) {
            awaitObserver(observers.get(i), futures.get(i), observerTimeout);
        }
    }

    private Future<?> submitObserver(McpInvocationObserver observer, McpInvocation invocation, Map<String, String> mdc) {
        try {
            return executor.submit(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    observer.onInvocationCompleted(invocation);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    private void awaitObserver(McpInvocationObserver observer, Future<?> future, Duration observerTimeout) {
        if (future == null) {
            log.warn("McpInvocationObserver {} skipped: the server is shutting down", observer.getClass().getName());
            return;
        }
        try {
            future.get(observerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("McpInvocationObserver {} did not complete within {}", observer.getClass().getName(), observerTimeout);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            log.warn("McpInvocationObserver {} failed", observer.getClass().getName(), e.getCause());
        }
    }

    private Duration timeout(String tool) {
        McpProperties.ToolProperties toolProperties = properties.getTools().get(tool);
        return toolProperties != null && toolProperties.getTimeout() != null
                ? toolProperties.getTimeout() : properties.getExecution().getTimeout();
    }

    private long maxResponseSize(String tool) {
        McpProperties.ToolProperties toolProperties = properties.getTools().get(tool);
        return (toolProperties != null && toolProperties.getMaxResponseSize() != null
                ? toolProperties.getMaxResponseSize() : properties.getLimits().getMaxResponseSize()).toBytes();
    }

    private static long serializedSize(CallToolResult result) {
        try {
            return McpJsonDefaults.getMapper().writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            log.warn("Could not determine the size of a tool result", e);
            return 0;
        }
    }

    private static String errorCode(CallToolResult result) {
        if (!Boolean.TRUE.equals(result.isError())) {
            return null;
        }
        if (result.structuredContent() instanceof Map<?, ?> structured && structured.get("code") instanceof String code) {
            return code;
        }
        return McpErrorCode.INTERNAL_ERROR.name();
    }

    private static CallToolResult error(String code, String message) {
        String safeMessage = message != null ? message : "Internal error";
        return CallToolResult.builder()
                .isError(true)
                .addContent(new TextContent(safeErrorCode(code) + ": " + safeMessage))
                .structuredContent(Map.of("code", safeErrorCode(code), "message", safeMessage))
                .build();
    }

    /**
     * A {@link ch.admin.bit.jeap.mcp.McpToolException} may carry a null code (it is service-supplied); callers
     * such as Micrometer's {@code KeyValue} reject a null value outright, so every use of a failure's code,
     * here and in {@link #observed}, goes through this fallback instead of the raw value.
     */
    private static String safeErrorCode(String code) {
        return code != null ? code : McpErrorCode.INTERNAL_ERROR.name();
    }

    private static String principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * Passed to {@link Observation#error} purely to flag a classified failure on the observation/span; never
     * thrown or exposed to a caller, so its message (the error code) is the only thing that matters -- no need
     * to pay for a stack trace.
     */
    private static final class ClassifiedFailure extends RuntimeException {
        ClassifiedFailure(String code) {
            super(code, null, false, false);
        }
    }
}
