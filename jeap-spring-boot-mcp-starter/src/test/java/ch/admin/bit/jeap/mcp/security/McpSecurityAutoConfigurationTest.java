package ch.admin.bit.jeap.mcp.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpSecurityAutoConfiguration.McpMethodSecurityConfiguration} is the one place that enables method
 * security on its own -- only when no {@code JeapJwtDecoderFactory} bean is present, i.e. no jEAP OAuth2
 * resource server is configured (see its Javadoc). With a resource server configured, the jEAP security
 * starter's own autoconfiguration is relied upon instead; that assumption is exercised end-to-end by the IT
 * suite, whose {@code McpTestApplication} deliberately carries no {@code @EnableMethodSecurity} of its own, so
 * a bypass there would show up as a real test failure instead of being masked.
 */
class McpSecurityAutoConfigurationTest {

    @Test
    void enablesMethodSecurityWhenNoResourceServerIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(McpSecurityAutoConfiguration.McpMethodSecurityConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean("preAuthorizeAuthorizationAdvisor"));
    }
}
