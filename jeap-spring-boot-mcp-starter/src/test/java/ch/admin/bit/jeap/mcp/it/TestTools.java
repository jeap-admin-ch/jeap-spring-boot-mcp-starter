package ch.admin.bit.jeap.mcp.it;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TestTools {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<io.micrometer.tracing.Tracer> tracer;

    @McpTool(name = "mdc_value", description = "Returns the MDC entry test-key as seen by the tool")
    @PreAuthorize("hasRole('READER')")
    public String mdcValue() {
        return String.valueOf(org.slf4j.MDC.get("test-key"));
    }

    @McpTool(name = "trace_id", description = "Returns the id of the trace the tool runs in")
    @PreAuthorize("hasRole('READER')")
    public String traceId() {
        var span = tracer.getObject().currentSpan();
        return span == null ? "none" : span.context().traceId();
    }

    public static final AtomicInteger EXECUTIONS = new AtomicInteger();

    @McpTool(name = "protected_echo", description = "Echoes the text, requires role READER")
    @PreAuthorize("hasRole('READER')")
    public String protectedEcho(@McpToolParam(description = "Text", required = true) String text) {
        EXECUTIONS.incrementAndGet();
        return text;
    }

    public static volatile java.util.concurrent.CountDownLatch SLEEP_STARTED = new java.util.concurrent.CountDownLatch(1);

    @McpTool(name = "sleep", description = "Sleeps for the given milliseconds")
    @PreAuthorize("hasRole('READER')")
    public String sleep(@McpToolParam(description = "Milliseconds", required = true) long ms) throws InterruptedException {
        SLEEP_STARTED.countDown();
        Thread.sleep(ms);
        return "slept";
    }

    public static volatile java.util.concurrent.CountDownLatch HOLD_STARTED = new java.util.concurrent.CountDownLatch(1);
    public static volatile java.util.concurrent.CountDownLatch HOLD_RELEASE = new java.util.concurrent.CountDownLatch(1);

    @McpTool(name = "hold", description = "Blocks until released by the test")
    @PreAuthorize("hasRole('READER')")
    public String hold() throws InterruptedException {
        // Held open until the test explicitly releases it, instead of racing a second call against a fixed
        // sleep duration -- a race a slower/loaded CI runner can lose (the second call arrives after the first
        // one already finished). A dedicated tool, not "sleep" with a negative ms: "sleep" has its own short
        // per-tool timeout (see application.yml, used by toolExceedingItsDeadlineTimesOut), far too short for a
        // human-paced test to reliably release within; this one relies on the generous global default instead.
        HOLD_STARTED.countDown();
        HOLD_RELEASE.await();
        return "released";
    }

    @McpTool(name = "big", description = "Returns a string of the given length")
    @PreAuthorize("hasRole('READER')")
    public String big(@McpToolParam(description = "Characters", required = true) int chars) {
        return "x".repeat(chars);
    }

    @McpTool(name = "fail", description = "Fails with an internal exception")
    @PreAuthorize("hasRole('READER')")
    public String fail() {
        throw new IllegalStateException("secret internal detail");
    }

    @McpTool(name = "business", description = "Fails with a business error")
    @PreAuthorize("hasRole('READER')")
    public String business() {
        throw new ch.admin.bit.jeap.mcp.McpToolException("SYSTEM_NOT_FOUND", "The system does not exist");
    }

    @McpTool(name = "business_without_code_or_message", description = "Fails with a business error that has neither a code nor a message")
    @PreAuthorize("hasRole('READER')")
    public String businessWithoutCodeOrMessage() {
        throw new ch.admin.bit.jeap.mcp.McpToolException(null, null);
    }

    @McpTool(name = "whoami", description = "Returns the authenticated name seen by the tool")
    @PreAuthorize("hasRole('READER')")
    public String whoami() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @McpTool(name = "public_echo", description = "Echoes the text, open to anonymous callers")
    @PreAuthorize("permitAll()")
    public String publicEcho(@McpToolParam(description = "Text", required = true) String text) {
        return text;
    }

    @McpTool(name = "mcp_protocol_error", description = "Throws a deliberate McpError, a protocol-level error, not a tool error")
    @PreAuthorize("hasRole('READER')")
    public String mcpProtocolError() {
        throw io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND.apply("urn:example:missing");
    }
}
