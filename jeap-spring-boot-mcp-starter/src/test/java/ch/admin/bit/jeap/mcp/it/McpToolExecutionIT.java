package ch.admin.bit.jeap.mcp.it;

import ch.admin.bit.jeap.mcp.McpInvocation;
import ch.admin.bit.jeap.mcp.test.McpTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolExecutionIT extends AbstractMcpIT {

    static final List<McpInvocation> INVOCATIONS = AbstractMcpIT.INVOCATIONS;

    @Autowired
    MeterRegistry meterRegistry;

    McpTestClient.ToolResult callAsReader(String tool, Map<String, Object> args) {
        try (McpTestClient client = client(tokens.withRoles("READER"))) {
            return client.call(tool, args);
        }
    }

    @BeforeEach
    void clear() {
        INVOCATIONS.clear();
    }

    @Test
    void businessErrorKeepsItsCodeAndMessage() {
        var result = callAsReader("business", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo("SYSTEM_NOT_FOUND");
        assertThat(result.text()).contains("The system does not exist");
    }

    @Test
    void unexpectedExceptionDoesNotLeakDetails() {
        var result = callAsReader("fail", Map.of());
        assertThat(result.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.text()).contains("correlationId=").doesNotContain("secret internal detail", "IllegalStateException");
    }

    @Test
    void businessErrorWithoutCodeOrMessageFallsBackInsteadOfCrashing() {
        // McpToolException(null, null): error() must not NPE building the structured error response.
        var result = callAsReader("business_without_code_or_message", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.text()).contains("Internal error");
    }

    @Test
    void toolExceedingItsDeadlineTimesOut() {
        assertThat(callAsReader("sleep", Map.of("ms", 5000)).errorCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void concurrencyLimitRejectsSecondCallUntilTheFirstTerminated() throws Exception {
        // The first call is held open by HOLD_RELEASE, not a fixed sleep duration: racing a second call
        // against a timed sleep was flaky under CI load, where the second call can arrive after the first one
        // already finished and released its permit.
        CompletableFuture<McpTestClient.ToolResult> first =
                CompletableFuture.supplyAsync(() -> callAsReader("hold", Map.of()));
        assertThat(TestTools.HOLD_STARTED.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callAsReader("hold", Map.of()).errorCode()).isEqualTo("CAPACITY_EXCEEDED");
        TestTools.HOLD_RELEASE.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).text()).contains("released");
        assertThat(callAsReader("hold", Map.of()).text()).contains("released");
    }

    @Test
    void oversizedResultIsRejectedNotTruncated() {
        assertThat(callAsReader("big", Map.of("chars", 100)).text()).contains("xxxx");
        var tooBig = callAsReader("big", Map.of("chars", 5000));
        assertThat(tooBig.errorCode()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(tooBig.text()).doesNotContain("xxxx");
    }

    @Test
    void oversizedRequestIsRejectedBeforeItReachesTheTool() throws Exception {
        HttpResponse<String> response = post(tokens.withRoles("READER"),
                toolCall("protected_echo", "{\"text\":\"" + "a".repeat(300 * 1024) + "\"}"));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("REQUEST_TOO_LARGE");
        assertThat(TestTools.EXECUTIONS).hasValue(0);
    }

    @Test
    void securityContextOfTheCallerIsAvailableInTheExecutionThread() {
        assertThat(callAsReader("whoami", Map.of()).text()).contains("test-user");
    }

    @Test
    void mdcOfTheRequestThreadIsAvailableInTheToolThread() {
        try (McpTestClient client = McpTestClient.connect(baseUrl(), "/mcp", tokens.withRoles("READER"),
                Map.of("X-Test-Mdc", "tenant-42"))) {
            assertThat(client.call("mdc_value").text()).contains("tenant-42");
        }
    }

    @Test
    void toolRunsInTheTraceOfTheIncomingRequest() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        try (McpTestClient client = McpTestClient.connect(baseUrl(), "/mcp", tokens.withRoles("READER"),
                Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"))) {
            assertThat(client.call("trace_id").text()).contains(traceId);
        }
    }

    @Test
    void manyParallelCallsAreAllAnsweredWithoutLeakingPermits() throws Exception {
        int calls = 100;
        try (ExecutorService pool = Executors.newFixedThreadPool(25);
             McpTestClient client = client(tokens.withRoles("READER"))) {
            List<CompletableFuture<McpTestClient.ToolResult>> futures = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                String text = "call-" + i;
                futures.add(CompletableFuture.supplyAsync(() -> client.call("protected_echo", Map.of("text", text)), pool));
            }
            long ok = 0;
            for (int i = 0; i < calls; i++) {
                var result = futures.get(i).get(60, TimeUnit.SECONDS);
                if (result.isError()) {
                    assertThat(result.errorCode()).isEqualTo("CAPACITY_EXCEEDED");
                } else {
                    assertThat(result.text()).contains("call-" + i);
                    ok++;
                }
            }
            assertThat(ok).isPositive();
            // every permit must be back: a sequential call after the burst has to succeed
            assertThat(client.call("protected_echo", Map.of("text", "after")).text()).contains("after");
        }
    }

    @Test
    void sequentialCallsFromDifferentUsersNeverMixSecurityContexts() {
        try (McpTestClient alice = client(tokens.forUser("alice", "READER"));
             McpTestClient bob = client(tokens.forUser("bob", "READER"))) {
            for (int i = 0; i < 5; i++) {
                assertThat(alice.call("whoami", Map.of()).text()).contains("alice").doesNotContain("bob");
                assertThat(bob.call("whoami", Map.of()).text()).contains("bob").doesNotContain("alice");
            }
        }
    }

    @Test
    void concurrentCallsFromDifferentUsersNeverMixSecurityContexts() throws Exception {
        // A small, shared pool forces the same worker thread to serve alice and bob repeatedly, in whatever
        // order the calls happen to interleave -- exactly the scenario where leftover thread-local security
        // context from a previous call would leak into the next one, if the invoker didn't clean up properly.
        int callsPerUser = 20;
        try (ExecutorService pool = Executors.newFixedThreadPool(4);
             McpTestClient alice = client(tokens.forUser("alice", "READER"));
             McpTestClient bob = client(tokens.forUser("bob", "READER"))) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < callsPerUser; i++) {
                futures.add(CompletableFuture.runAsync(
                        () -> assertThat(alice.call("whoami", Map.of()).text()).contains("alice").doesNotContain("bob"), pool));
                futures.add(CompletableFuture.runAsync(
                        () -> assertThat(bob.call("whoami", Map.of()).text()).contains("bob").doesNotContain("alice"), pool));
            }
            for (CompletableFuture<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void metricsAndObserversSeeEveryCall() {
        callAsReader("whoami", Map.of());
        callAsReader("business", Map.of());
        assertThat(meterRegistry.get("jeap.mcp.tool.calls").tag("tool", "business").tag("error", "SYSTEM_NOT_FOUND")
                .timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(meterRegistry.get("jeap.mcp.tool.calls").tag("tool", "whoami").tag("status", "success")
                .timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(INVOCATIONS).extracting(McpInvocation::tool).contains("whoami", "business");
        assertThat(INVOCATIONS).filteredOn(i -> i.tool().equals("business")).first()
                .extracting(McpInvocation::errorCode).isEqualTo("SYSTEM_NOT_FOUND");
    }

    @Test
    void anOverrunningObserverIsCutOffAndNeverDelaysOrFailsTheCall() {
        // slowObserver() (see AbstractMcpIT) sleeps far longer than jeap.mcp.execution.observer-timeout
        // (the application.yml default, 1s). The call must not wait for it: it's cancelled and logged, not
        // awaited to completion, and the tool result is unaffected either way.
        AbstractMcpIT.SLOW_OBSERVER_DELAY_MS.set(5000);
        long start = System.nanoTime();
        var result = callAsReader("whoami", Map.of());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(result.isError()).isFalse();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aFailingObserverDoesNotFailTheCallOrStopOtherObservers() {
        // AbstractMcpIT.ObserverConfig also registers a McpInvocationObserver that always throws; it runs for
        // this call too (alongside the recording one), and must not affect the result or the other observer.
        var result = callAsReader("whoami", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(INVOCATIONS).extracting(McpInvocation::tool).contains("whoami");
    }

    @Test
    void aiToolAnnotatedMethodGetsTheSameExecutionProtectionsAsAnMcpToolMethod() {
        // Published via @Tool (Spring AI's own annotation) and a ToolCallbackProvider bean, not @McpTool; see
        // AiToolTestTools. Confirms it goes through the same McpToolInvoker wrapping: default deny, metrics.
        var result = callAsReader("ai_protected_echo", Map.of("text", "hello"));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("hello");
        assertThat(meterRegistry.get("jeap.mcp.tool.calls").tag("tool", "ai_protected_echo").tag("status", "success")
                .timer().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aiToolWithAMissingRoleIsForbiddenNotARawAccessDeniedMessage() {
        // Before the error advisor's pointcut covered @Tool too, this AccessDeniedException was never
        // classified: Spring AI's own callback would have returned whatever its raw message happened to be,
        // not the controlled "FORBIDDEN: Access denied" an @McpTool method gets for the exact same failure.
        var result = callAsReader("ai_admin_only", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(result.text()).contains("Access denied");
    }

    @Test
    void aiToolUnexpectedExceptionDoesNotLeakDetails() {
        var result = callAsReader("ai_fail", Map.of());
        assertThat(result.errorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.text()).contains("correlationId=").doesNotContain("secret internal detail", "IllegalStateException");
    }

    @Test
    void aiToolBusinessErrorKeepsItsCodeAndMessage() {
        var result = callAsReader("ai_business", Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo("SYSTEM_NOT_FOUND");
        assertThat(result.text()).contains("The system does not exist");
    }

    @Test
    void mcpErrorIsRethrownAsAGenuineJsonRpcErrorNotATooResult() throws Exception {
        // A thrown McpError is deliberate on the tool's part (see TestTools): the invoker must let it reach
        // the transport unwrapped, as a top-level JSON-RPC "error" (McpSchema.JSONRPCResponse.error), rather
        // than classifying it into an isError=true tool result like every other exception.
        HttpResponse<String> response = post(tokens.withRoles("READER"), toolCall("mcp_protocol_error", "{}"));
        assertThat(response.body()).contains("\"error\"").doesNotContain("\"isError\"");
        assertThat(response.body()).contains("-32002");
    }
}
