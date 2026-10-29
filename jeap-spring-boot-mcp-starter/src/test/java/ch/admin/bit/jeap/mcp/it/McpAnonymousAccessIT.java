package ch.admin.bit.jeap.mcp.it;

import ch.admin.bit.jeap.mcp.test.McpTestSupportConfiguration;
import ch.admin.bit.jeap.mcp.test.McpTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code jeap.mcp.security.anonymous-access=true} on its own application instance and port: that
 * property changes the endpoint's security chain, so it cannot share a context (and so the port from
 * {@link AbstractMcpIT}) with the default-configuration ITs.
 */
@SpringBootTest(classes = McpTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "jeap.mcp.security.anonymous-access=true")
@Import(McpTestSupportConfiguration.class)
class McpAnonymousAccessIT {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/mcp-test-jwks/jwks.json");
    }

    @Autowired
    private McpTestTokens tokens;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anonymousCallToAPublicToolSucceeds() throws Exception {
        HttpResponse<String> response = post(null, toolCall("public_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello");
    }

    @Test
    void anonymousCallToAProtectedToolIsForbiddenButNotExecuted() throws Exception {
        int before = TestTools.EXECUTIONS.get();
        HttpResponse<String> response = post(null, toolCall("protected_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(200); // the HTTP request succeeds, the tool call itself fails
        assertThat(response.body()).contains("FORBIDDEN");
        assertThat(TestTools.EXECUTIONS).hasValue(before);
    }

    @Test
    void authenticatedCallToAProtectedToolStillWorks() throws Exception {
        HttpResponse<String> response = post(tokens.withRoles("READER"), toolCall("protected_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello").doesNotContain("FORBIDDEN");
    }

    @Test
    void aPresentButInvalidTokenIsStillRejectedWith401() throws Exception {
        HttpResponse<String> response = post(tokens.expired("READER"), toolCall("public_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> post(String bearerToken, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String toolCall(String tool, String arguments) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}""".formatted(tool, arguments);
    }

    private static String baseUrl() {
        return "http://localhost:" + PORT;
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
