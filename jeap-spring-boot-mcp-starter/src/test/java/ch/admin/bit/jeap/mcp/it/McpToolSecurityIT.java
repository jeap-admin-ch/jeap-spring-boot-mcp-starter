package ch.admin.bit.jeap.mcp.it;

import ch.admin.bit.jeap.mcp.test.McpTestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolSecurityIT extends AbstractMcpIT {

    @Test
    void clientSeesToolsAndExecutesAuthorizedCall() {
        try (McpTestClient client = client(tokens.withRoles("READER"))) {
            assertThat(client.listTools()).extracting(t -> t.name()).contains("protected_echo", "whoami");
            var result = client.call("protected_echo", Map.of("text", "hello"));
            assertThat(result.isError()).isFalse();
            assertThat(result.text()).contains("hello");
        }
        assertThat(TestTools.EXECUTIONS).hasValue(1);
    }

    @Test
    void userWithoutRoleIsDeniedAndToolIsNotExecuted() {
        try (McpTestClient client = client(tokens.withRoles("OTHER"))) {
            var result = client.call("protected_echo", Map.of("text", "hello"));
            assertThat(result.isError()).isTrue();
            assertThat(result.errorCode()).isEqualTo("FORBIDDEN");
        }
        assertThat(TestTools.EXECUTIONS).hasValue(0);
    }

    @Test
    void requestWithoutTokenGets401WithDiscoveryHint() throws Exception {
        HttpResponse<String> response = post(null, toolCall("protected_echo", "{\"text\":\"x\"}"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(TestTools.EXECUTIONS).hasValue(0);
    }

    @Test
    void requestWithoutTokenGets401EvenForAPublicTool() throws Exception {
        // jeap.mcp.security.anonymous-access is off by default: the endpoint requires a token regardless of what
        // an individual tool's own @PreAuthorize rule allows.
        HttpResponse<String> response = post(null, toolCall("public_echo", "{\"text\":\"x\"}"));
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void invalidTokensAreRejectedAtHttpLevel() throws Exception {
        String body = toolCall("protected_echo", "{\"text\":\"x\"}");
        for (String token : new String[]{
                tokens.expired("READER"),
                tokens.signedByUnknownKey("READER"),
                tokens.forAudience("some-other-service", "READER"),
                tokens.fromUnknownIssuer("READER"),
                "not-a-jwt"}) {
            assertThat(post(token, body).statusCode()).as(token).isEqualTo(401);
        }
        assertThat(TestTools.EXECUTIONS).hasValue(0);
    }

    @Test
    void clientCannotConnectWithInvalidToken() {
        assertThatThrownBy(() -> client(tokens.expired("READER"))).isNotNull();
    }

    @Test
    void protectedResourceMetadataIsPublicAndPointsToTheAuthorizationServer() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/.well-known/oauth-protected-resource")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("https://test.example.ch/mcp", "http://localhost/auth");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/other"})
    void unrelatedPathsAreNotOpenedUp(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isIn(401, 403, 404);
    }
}
