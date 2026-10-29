package ch.admin.bit.jeap.mcp.execution;

import ch.admin.bit.jeap.mcp.McpInvocationObserver;
import ch.admin.bit.jeap.mcp.config.McpProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties.ApiType;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties.ServerProtocol;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;

import java.util.Set;
import java.util.TreeSet;

/**
 * Protection and operations features for MCP tool calls: execution limits, safe error mapping, metrics and logging.
 */
@AutoConfiguration
@ConditionalOnClass(SyncToolSpecification.class)
@EnableConfigurationProperties(McpProperties.class)
public class McpExecutionAutoConfiguration {

    @Bean(destroyMethod = "close")
    McpToolInvoker mcpToolInvoker(McpProperties properties, ObjectProvider<MeterRegistry> meterRegistry,
                                  ObjectProvider<ObservationRegistry> observationRegistry,
                                  ObjectProvider<McpInvocationObserver> observers) {
        return new McpToolInvoker(properties, meterRegistry.getIfAvailable(() -> Metrics.globalRegistry),
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                observers.orderedStream().toList());
    }

    @Bean
    static BeanPostProcessor mcpToolSpecificationPostProcessor(BeanFactory beanFactory) {
        return new McpToolSpecificationPostProcessor(beanFactory.getBeanProvider(McpToolInvoker.class));
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor mcpToolErrorAdvisor() {
        // Spring AI's own @Tool is explicitly supported too (see AiToolTestTools, docs/security.md): without
        // this, a @Tool method's exception is never classified, so McpInvocationScope stays empty and the raw
        // exception message (and an AccessDeniedException's real status) reaches the client unfiltered.
        ComposablePointcut pointcut = new ComposablePointcut(new AnnotationMatchingPointcut(null, McpTool.class, true))
                .union(new AnnotationMatchingPointcut(null, Tool.class, true));
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, new McpToolErrorInterceptor());
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE); // outside of method security
        return advisor;
    }

    @Bean
    SmartInitializingSingleton mcpServerProfileValidator(McpServerProperties serverProperties) {
        // Execution limits, the error contract and the authorization bypass checks in this starter are only
        // implemented and verified for a SYNC/STATELESS server: STATELESS is what makes a per-call deadline,
        // concurrency limit and McpInvocationScope safe to reason about, and SYNC is what McpToolInvoker's own
        // wrapping assumes (see McpToolSpecificationPostProcessor). Spring AI defaults to STREAMABLE, not
        // STATELESS, so this would otherwise silently run unprotected under the wrong profile.
        return () -> {
            if (serverProperties.getType() != ApiType.SYNC || serverProperties.getProtocol() != ServerProtocol.STATELESS) {
                throw new IllegalStateException("jeap-spring-boot-mcp-starter requires spring.ai.mcp.server.type=SYNC and "
                        + "spring.ai.mcp.server.protocol=STATELESS, but found type=" + serverProperties.getType()
                        + " and protocol=" + serverProperties.getProtocol() + ".");
            }
        };
    }

    @Bean
    SmartInitializingSingleton mcpToolOverrideValidator(McpProperties properties, McpToolInvoker invoker) {
        return () -> {
            Set<String> unknown = new TreeSet<>(properties.getTools().keySet());
            unknown.removeAll(invoker.knownTools());
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("jeap.mcp.tools configures unknown tools " + unknown
                        + ", the published tools are " + new TreeSet<>(invoker.knownTools()));
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class RequestSizeLimitConfiguration {

        @Bean
        FilterRegistrationBean<McpRequestSizeFilter> mcpRequestSizeFilter(
                McpProperties properties,
                @Value("${spring.ai.mcp.server.stateless.mcp-endpoint:/mcp}") String mcpEndpoint) {
            FilterRegistrationBean<McpRequestSizeFilter> registration = new FilterRegistrationBean<>(
                    new McpRequestSizeFilter(properties.getLimits().getMaxRequestSize().toBytes()));
            registration.addUrlPatterns(mcpEndpoint);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
