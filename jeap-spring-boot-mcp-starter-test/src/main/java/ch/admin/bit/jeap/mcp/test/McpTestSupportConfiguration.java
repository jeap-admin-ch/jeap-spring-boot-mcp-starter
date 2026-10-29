package ch.admin.bit.jeap.mcp.test;

import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.jws.TestKeyProvider;
import ch.admin.bit.jeap.security.test.jws.configuration.JwsTestSupportConfiguration;
import ch.admin.bit.jeap.security.test.resource.configuration.DisableJeapPermitAllSecurityConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Import into a {@code @SpringBootTest(webEnvironment = DEFINED_PORT)} to test MCP tools over real HTTP with real
 * signed tokens. It publishes a JWKS endpoint at {@code /mcp-test-jwks/jwks.json} in the application under test and
 * provides {@link McpTestTokens}. The resource server's issuer and JWK set URI are needed to build the security
 * filter chain at context startup, before the embedded server has a port -- so {@code RANDOM_PORT} does not work
 * here; pick a free port yourself and wire it in via {@code @DynamicPropertySource} instead:
 *
 * <pre>
 * &#64;SpringBootTest(webEnvironment = DEFINED_PORT)
 * &#64;Import(McpTestSupportConfiguration.class)
 * class MyIT {
 *     private static final int PORT = ...; // e.g. pick a free port
 *
 *     &#64;DynamicPropertySource
 *     static void properties(DynamicPropertyRegistry registry) {
 *         registry.add("server.port", () -&gt; PORT);
 *         registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
 *                 () -&gt; "http://localhost:" + PORT + "/mcp-test-jwks/jwks.json");
 *     }
 * }
 * </pre>
 *
 * with {@code jeap.security.oauth2.resourceserver.authorization-server.issuer: http://localhost/auth} set in
 * {@code application.yml}. See {@code docs/testing.md} for the full pattern. {@link McpTestClient} then talks
 * to the server like a real MCP client.
 */
@Configuration
@Import({DisableJeapPermitAllSecurityConfiguration.class, JwsTestSupportConfiguration.class, McpTestJwksEndpoint.class})
public class McpTestSupportConfiguration {

    @Bean
    com.nimbusds.jose.jwk.RSAKey mcpTestSigningKey(TestKeyProvider testKeyProvider) {
        return testKeyProvider.getAuthServerKey();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    SecurityFilterChain mcpTestJwksFilterChain(HttpSecurity http) {
        http.securityMatcher(McpTestJwksEndpoint.PATH)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    McpTestTokens mcpTestTokens(JwsBuilderFactory jwsBuilderFactory,
                                @Value("${jeap.security.oauth2.resourceserver.authorization-server.issuer}") String issuer,
                                @Value("${spring.application.name}") String audience) {
        return new McpTestTokens(jwsBuilderFactory, issuer, audience);
    }
}
