package ch.admin.bit.jeap.mcp.it;

import ch.admin.bit.jeap.mcp.test.McpTestClient;
import ch.admin.bit.jeap.mcp.test.McpTestSupportConfiguration;
import ch.admin.bit.jeap.mcp.test.McpTestTokens;
import ch.admin.bit.jeap.mcp.McpInvocation;
import ch.admin.bit.jeap.mcp.McpInvocationObserver;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts the application on a real port with a real JWKS endpoint. Tests talk to it over HTTP with real signed
 * tokens, either through a real MCP client or with raw HTTP requests.
 */
@SpringBootTest(classes = McpTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({McpTestSupportConfiguration.class, AbstractMcpIT.ObserverConfig.class})
abstract class AbstractMcpIT {

    // The resource server resolves its JWKS URI at startup, so the port must be known before the context starts
    static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/mcp-test-jwks/jwks.json");
    }

    static final List<McpInvocation> INVOCATIONS = new CopyOnWriteArrayList<>();

    // How long slowObserver() sleeps; 0 (the default, reset between tests) means it returns immediately. Only
    // the one test for the observer timeout sets this.
    static final AtomicLong SLOW_OBSERVER_DELAY_MS = new AtomicLong(0);

    // One shared configuration for all ITs so that a single application instance (and port) serves them
    @TestConfiguration
    static class ObserverConfig {
        // Puts a request header into the MDC like application filters do, to check it reaches the tool thread
        @Bean
        FilterRegistrationBean<Filter> testMdcFilter() {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                String value = ((HttpServletRequest) request).getHeader("X-Test-Mdc");
                if (value != null) {
                    MDC.put("test-key", value);
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove("test-key");
                }
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }

        @Bean
        McpInvocationObserver recordingObserver() {
            return INVOCATIONS::add;
        }

        @Bean
        McpInvocationObserver failingObserver() {
            return invocation -> {
                throw new IllegalStateException("observer failure must not matter");
            };
        }

        @Bean
        McpInvocationObserver slowObserver() {
            return invocation -> {
                long delay = SLOW_OBSERVER_DELAY_MS.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        }
    }

    @Autowired
    protected McpTestTokens tokens;

    protected final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetToolState() {
        TestTools.EXECUTIONS.set(0);
        TestTools.SLEEP_STARTED = new CountDownLatch(1);
        TestTools.HOLD_STARTED = new CountDownLatch(1);
        TestTools.HOLD_RELEASE = new CountDownLatch(1);
        SLOW_OBSERVER_DELAY_MS.set(0);
    }

    protected static String baseUrl() {
        return "http://localhost:" + PORT;
    }

    protected McpTestClient client(String token) {
        return McpTestClient.connect(baseUrl(), "/mcp", token);
    }

    /** Raw JSON-RPC POST, for the cases a well-behaved client can't produce. */
    protected HttpResponse<String> post(String bearerToken, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String toolCall(String tool, String arguments) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}""".formatted(tool, arguments);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
