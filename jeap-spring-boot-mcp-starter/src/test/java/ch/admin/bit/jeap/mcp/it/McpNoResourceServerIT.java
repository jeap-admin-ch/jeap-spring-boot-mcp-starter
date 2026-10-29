package ch.admin.bit.jeap.mcp.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Exercises {@code jeap.mcp.security.anonymous-access=true} with no jEAP OAuth2 resource server configured at
 * all (the shared test {@code application.yml} sets an issuer, overridden back to blank here) -- a different
 * mode from {@link McpAnonymousAccessIT}, which still has one. No token is ever issued or validated: there is
 * no {@code McpTestSupportConfiguration} JWKS endpoint to import, since nothing could call it.
 */
@SpringBootTest(classes = McpTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "jeap.mcp.security.anonymous-access=true",
                "jeap.security.oauth2.resourceserver.authorization-server.issuer="
        })
class McpNoResourceServerIT {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void anonymousCallToAPublicToolSucceeds() throws Exception {
        HttpResponse<String> response = post(toolCall("public_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello");
    }

    @Test
    void protectedToolIsPermanentlyForbiddenSinceNoTokenCanEverBeValidated() throws Exception {
        int before = TestTools.EXECUTIONS.get();
        HttpResponse<String> response = post(toolCall("protected_echo", "{\"text\":\"hello\"}"));
        assertThat(response.statusCode()).isEqualTo(200); // the HTTP request succeeds, the tool call itself fails
        assertThat(response.body()).contains("FORBIDDEN");
        assertThat(TestTools.EXECUTIONS).hasValue(before);
    }

    @Test
    void protectedResourceMetadataIsNotPublished() throws Exception {
        // No handler is registered for this path (oauth2ResourceServer().protectedResourceMetadata(...) is only
        // configured when a resource server exists), so Spring forwards to the error page; that forward is a
        // separate dispatch to /error, which this chain's securityMatcher does not cover, so the jEAP security
        // starter's own deny-all fallback denies it with 403 instead of a plain 404 reaching the client.
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(403, 404);
        assertThat(response.body()).doesNotContain("\"resource\"");
    }

    @Test
    void aPresentBearerTokenChangesNothingSinceThereIsNoDecoderToValidateItWith() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer not-a-jwt")
                .POST(HttpRequest.BodyPublishers.ofString(toolCall("public_echo", "{\"text\":\"hello\"}")));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hello");
    }

    private HttpResponse<String> post(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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
