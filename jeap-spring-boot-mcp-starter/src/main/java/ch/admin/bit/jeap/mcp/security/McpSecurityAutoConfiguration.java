package ch.admin.bit.jeap.mcp.security;

import ch.admin.bit.jeap.security.resource.configuration.JeapOauth2ResourceAccessDeniedHandler;
import ch.admin.bit.jeap.security.resource.configuration.JeapOauth2ResourceAuthenticationEntryPoint;
import ch.admin.bit.jeap.security.resource.configuration.MvcSecurityConfiguration;
import ch.admin.bit.jeap.mcp.config.McpProperties;
import ch.admin.bit.jeap.security.resource.properties.AuthorizationServerConfigProperties;
import ch.admin.bit.jeap.security.resource.properties.ResourceServerProperties;
import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import ch.admin.bit.jeap.mcp.execution.McpToolInvoker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Optional;

/**
 * Protects the MCP endpoint with a dedicated, stateless security filter chain. In contrast to the default jEAP
 * resource server chain, CSRF protection is disabled: MCP clients authenticate with a bearer token only and expect
 * a 401 response with a {@code WWW-Authenticate} challenge (not a 403 from the CSRF filter) to start the OAuth
 * discovery. JWT validation and the authentication token are the ones of the jEAP security starter, when one is
 * configured at all: {@code jeap.mcp.security.anonymous-access} also covers running with no jEAP OAuth2 resource
 * server configured whatsoever, see {@link #mcpSecurityFilterChain}.
 */
@AutoConfiguration(after = MvcSecurityConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class McpSecurityAutoConfiguration {

    private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";

    @Bean
    static McpToolAuthorizationValidator mcpToolAuthorizationValidator(ConfigurableListableBeanFactory beanFactory,
                                                                         ObjectProvider<McpToolInvoker> toolInvoker) {
        return new McpToolAuthorizationValidator(beanFactory, toolInvoker);
    }

    /**
     * The jEAP security starter only enables method security ({@code @PreAuthorize}) once its resource server is
     * configured (see {@code MvcSecurityConfiguration}). Without a resource server at all, {@code @PreAuthorize}
     * on {@code @McpTool} methods would otherwise be silently ignored, defeating the default-deny check above.
     */
    @Configuration
    @ConditionalOnMissingBean(JeapJwtDecoderFactory.class)
    @EnableMethodSecurity
    static class McpMethodSecurityConfiguration {
    }

    /**
     * Without any jEAP OAuth2 resource server configured ({@link JeapJwtDecoderFactory} absent), the MCP endpoint
     * can only ever be reached anonymously -- there is no decoder to validate a bearer token with, so
     * {@code jeap.mcp.security.anonymous-access} is mandatory in that case; a tool that still requires a role is
     * then permanently unreachable, which is the expected, if unusual, outcome of running with no resource server.
     * The OAuth protected resource metadata is not published either, since there is no authorization server to
     * describe. With a resource server configured, behaviour is unchanged from before: default deny, a present
     * but invalid or expired token is still rejected with 401 by the JWT filter, and {@code anonymous-access}
     * only decides whether the absence of a token is tolerated.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 100) // Before the default jEAP chain, but allow for application chains
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                               ObjectProvider<JeapJwtDecoderFactory> jeapJwtDecoderFactory,
                                               AuthoritiesResolver authoritiesResolver,
                                               ObjectProvider<ResourceServerProperties> resourceServerProperties,
                                               McpProperties mcpProperties,
                                               Optional<JeapOauth2ResourceAuthenticationEntryPoint> authenticationEntryPoint,
                                               Optional<JeapOauth2ResourceAccessDeniedHandler> accessDeniedHandler,
                                               @Value("${spring.ai.mcp.server.stateless.mcp-endpoint:/mcp}") String mcpEndpoint) {
        boolean anonymousAccess = mcpProperties.getSecurity().isAnonymousAccess();
        JeapJwtDecoderFactory decoderFactory = jeapJwtDecoderFactory.getIfAvailable();

        http.securityMatcher(mcpEndpoint, PROTECTED_RESOURCE_METADATA, PROTECTED_RESOURCE_METADATA + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (decoderFactory == null) {
            Assert.isTrue(anonymousAccess, "No jEAP OAuth2 resource server is configured (see the jEAP security " +
                    "starter), so the MCP endpoint can only ever be reached anonymously. Set " +
                    "jeap.mcp.security.anonymous-access: true to allow that, or configure a resource server.");
            // Every tool still default-denies on its own; without a decoder there is no way to ever be anything
            // but anonymous, so only a tool with @PreAuthorize("permitAll()") is ever reachable.
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
            return http.build();
        }

        String resourceUri = mcpProperties.getSecurity().getResourceUri();
        Assert.hasText(resourceUri, "jeap.mcp.security.resource-uri must be set to the external URL of the MCP endpoint");
        List<String> authorizationServers = resourceServerProperties.getObject().getAllAuthServerConfigurations().stream()
                .map(AuthorizationServerConfigProperties::getIssuer).toList();

        http.authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(PROTECTED_RESOURCE_METADATA, PROTECTED_RESOURCE_METADATA + "/**").permitAll();
                    // A present but invalid or expired token is still rejected with 401 by the JWT filter below;
                    // this only decides whether the absence of a token is tolerated. Each tool still default-denies
                    // on its own, a public one needs an explicit @PreAuthorize("permitAll()").
                    if (anonymousAccess) {
                        authorize.anyRequest().permitAll();
                    } else {
                        authorize.anyRequest().fullyAuthenticated();
                    }
                })
                .oauth2ResourceServer(resourceServer -> {
                    resourceServer.protectedResourceMetadata(metadata -> metadata
                            .protectedResourceMetadataCustomizer(builder -> {
                                builder.resource(resourceUri);
                                authorizationServers.forEach(builder::authorizationServer);
                            }));
                    resourceServer.jwt(jwt -> jwt
                            .decoder(decoderFactory.createJwtDecoder())
                            .jwtAuthenticationConverter(new JeapAuthenticationConverter(authoritiesResolver)));
                    authenticationEntryPoint.ifPresent(resourceServer::authenticationEntryPoint);
                    accessDeniedHandler.ifPresent(resourceServer::accessDeniedHandler);
                });
        return http.build();
    }
}
