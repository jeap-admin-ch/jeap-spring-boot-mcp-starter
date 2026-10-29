package ch.admin.bit.jeap.mcp.test;

import ch.admin.bit.jeap.security.test.jws.JwsBuilder;
import ch.admin.bit.jeap.security.test.jws.JwsBuilderFactory;
import ch.admin.bit.jeap.security.test.jws.RSAKeyUtils;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationContext;
import lombok.RequiredArgsConstructor;

import java.time.temporal.ChronoUnit;

/**
 * Creates really signed JWTs for tests. They are signed with the key that the jEAP test JWKS endpoint of the
 * application under test publishes, so the starter validates them exactly like tokens from a real authorization
 * server: signature, expiry, issuer and audience.
 */
@RequiredArgsConstructor
public class McpTestTokens {

    private final JwsBuilderFactory jwsBuilderFactory;
    private final String issuer;
    private final String audience;

    /** A valid token for the application under test carrying the given user roles. */
    public String withRoles(String... roles) {
        return validBuilder().withUserRoles(roles).build().serialize();
    }

    /**
     * A valid token for the application under test, with a custom subject instead of the default "test-user".
     * Lets tests check that calls from different users never mix security contexts.
     */
    public String forUser(String subject, String... roles) {
        return jwsBuilderFactory.createValidFromNowBuilder(subject, JeapAuthenticationContext.USER, 1, ChronoUnit.HOURS)
                .withIssuer(issuer).withAudiences(audience).withUserRoles(roles).build().serialize();
    }

    /** A token that is already expired. */
    public String expired(String... roles) {
        return jwsBuilderFactory.createValidFromNowBuilder("test-user", JeapAuthenticationContext.USER, -1, ChronoUnit.HOURS)
                .withIssuer(issuer).withAudiences(audience).withUserRoles(roles).build().serialize();
    }

    /** A valid token issued for a different audience. */
    public String forAudience(String otherAudience, String... roles) {
        return jwsBuilderFactory.createValidFromNowBuilder("test-user", JeapAuthenticationContext.USER, 1, ChronoUnit.HOURS)
                .withIssuer(issuer).withAudiences(otherAudience).withUserRoles(roles).build().serialize();
    }

    /** A valid-looking token with the right claims signed with a key the server does not know. */
    public String signedByUnknownKey(String... roles) {
        return JwsBuilder.createValidFromNow("test-user", JeapAuthenticationContext.USER, 1, ChronoUnit.HOURS)
                .withRsaKey(RSAKeyUtils.createRsaKeyPair())
                .withIssuer(issuer).withAudiences(audience).withUserRoles(roles).build().serialize();
    }

    /** A token with the right claims and key but an issuer the server does not trust. */
    public String fromUnknownIssuer(String... roles) {
        return jwsBuilderFactory.createValidFromNowBuilder("test-user", JeapAuthenticationContext.USER, 1, ChronoUnit.HOURS)
                .withIssuer("http://evil.example/realm").withAudiences(audience).withUserRoles(roles).build().serialize();
    }

    private JwsBuilder validBuilder() {
        return jwsBuilderFactory.createValidFromNowBuilder("test-user", JeapAuthenticationContext.USER, 1, ChronoUnit.HOURS)
                .withIssuer(issuer).withAudiences(audience);
    }
}
