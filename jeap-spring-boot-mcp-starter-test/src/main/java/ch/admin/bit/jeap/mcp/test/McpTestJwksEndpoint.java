package ch.admin.bit.jeap.mcp.test;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public part of the test signing key. It lives under its own path so that it doesn't shadow
 * {@code /.well-known/oauth-protected-resource}, which the jEAP test JWKS mock would do with its
 * {@code /.well-known/**} security matcher.
 */
@RestController
class McpTestJwksEndpoint {

    static final String PATH = "/mcp-test-jwks/jwks.json";

    private final RSAKey key;

    McpTestJwksEndpoint(RSAKey key) {
        this.key = key;
    }

    @GetMapping(PATH)
    Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }
}
