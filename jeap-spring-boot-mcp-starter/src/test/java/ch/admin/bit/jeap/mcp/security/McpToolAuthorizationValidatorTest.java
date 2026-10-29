package ch.admin.bit.jeap.mcp.security;

import ch.admin.bit.jeap.mcp.config.McpProperties;
import ch.admin.bit.jeap.mcp.execution.McpToolInvoker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolAuthorizationValidatorTest {

    @Configuration
    @EnableMethodSecurity
    static class ValidatorConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @PreAuthorize("hasRole('READER')")
    @interface ReaderOnly {
    }

    static class MethodRule {
        @McpTool(name = "a", description = "a")
        @PreAuthorize("hasRole('READER')")
        public String a() {
            return "a";
        }
    }

    @PreAuthorize("hasRole('READER')")
    static class ClassRule {
        @McpTool(name = "b", description = "b")
        public String b() {
            return "b";
        }
    }

    static class MetaRule {
        @McpTool(name = "c", description = "c")
        @ReaderOnly
        public String c() {
            return "c";
        }
    }

    static class NoRule {
        @McpTool(name = "unprotected", description = "no rule")
        public String d() {
            return "d";
        }
    }

    static class NoTool {
        public String e() {
            return "e";
        }
    }

    static class AiToolWithoutRule {
        @Tool(name = "aiUnprotected", description = "no rule")
        public String f() {
            return "f";
        }
    }

    interface ToolApi {
        String call();
    }

    static class UnprotectedToolImpl implements ToolApi {
        @McpTool(name = "lazyUnprotected", description = "no rule")
        @Override
        public String call() {
            return "x";
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class LazyInterfaceBeanConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }

        // Declared return type is the interface, not the implementing class, and @Lazy keeps it uninstantiated
        // until first use -- exactly the case where naive bean type lookup would miss the tool on the real class.
        @Bean
        @Lazy
        ToolApi lazyTool() {
            return new UnprotectedToolImpl();
        }
    }

    @Test
    void toolsWithAuthorizationRuleAreAccepted() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorConfig.class)
                .withBean(MethodRule.class).withBean(ClassRule.class).withBean(MetaRule.class).withBean(NoTool.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    static class ValidatorWithoutMethodSecurityConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }
    }

    @Test
    void toolsWithoutMethodSecurityFailStartup() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorWithoutMethodSecurityConfig.class)
                .withBean(MethodRule.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("method security", "@EnableMethodSecurity");
                });
    }

    @Test
    void noToolsDoNotRequireMethodSecurity() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorWithoutMethodSecurityConfig.class)
                .withBean(NoTool.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void toolWithoutAuthorizationRuleFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorConfig.class)
                .withBean(NoRule.class).withBean(MethodRule.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("'unprotected'", "NoRule#d")
                            .doesNotContain("MethodRule");
                });
    }

    @Test
    void lazyToolBeanDeclaredAsInterfaceIsStillChecked() {
        new ApplicationContextRunner().withUserConfiguration(LazyInterfaceBeanConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("'lazyUnprotected'");
                });
    }

    @Test
    void aiToolWithoutAuthorizationRuleFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorConfig.class)
                .withBean(AiToolWithoutRule.class).withBean(MethodRule.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("'aiUnprotected'", "AiToolWithoutRule#f")
                            .doesNotContain("MethodRule");
                });
    }

    @Configuration
    static class UnverifiableToolConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }

        // Simulates a tool published through a hand-built ToolCallback/SyncToolSpecification, bypassing both
        // @McpTool and @Tool -- the one case the annotation scan above can never see on its own.
        @Bean(destroyMethod = "close")
        McpToolInvoker toolInvoker() {
            McpToolInvoker invoker = new McpToolInvoker(new McpProperties(), new SimpleMeterRegistry(),
                    ObservationRegistry.NOOP, List.of());
            invoker.wrap(SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("handBuilt").description("no backing method").build())
                    .callHandler((context, request) -> null)
                    .build());
            return invoker;
        }
    }

    @Test
    void toolWithoutAnyBackingAnnotationFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(UnverifiableToolConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("handBuilt");
                });
    }

    static class FinalMethodTool {
        // A security proxy (CGLIB) cannot override a final method, so this @PreAuthorize can never actually be
        // enforced -- Spring AI would invoke this directly on the proxy instance.
        @McpTool(name = "finalTool", description = "final method")
        @PreAuthorize("hasRole('READER')")
        public final String g() {
            return "g";
        }
    }

    @Test
    void finalToolMethodFailsStartupEvenWithAnAuthorizationRule() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorConfig.class)
                .withBean(FinalMethodTool.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("'finalTool'", "final", "not public", "FinalMethodTool#g");
                });
    }

    static class NonPublicMethodTool {
        @McpTool(name = "nonPublicTool", description = "non-public method")
        @PreAuthorize("hasRole('READER')")
        String h() {
            return "h";
        }
    }

    @Test
    void nonPublicToolMethodFailsStartupEvenWithAnAuthorizationRule() {
        new ApplicationContextRunner().withUserConfiguration(ValidatorConfig.class)
                .withBean(NonPublicMethodTool.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("'nonPublicTool'");
                });
    }

    static class AiToolNotPublished {
        @Tool(name = "notPublished", description = "not exposed via MCP, e.g. used for a service's own ChatClient calls")
        public String notPublished() {
            return "internal";
        }
    }

    static class AiToolPublished {
        @Tool(name = "published", description = "exposed via MCP")
        public String published() {
            return "x";
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class EmptyInvokerConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }

        // A real McpToolInvoker that has not published anything, so a scanned @Tool method with no matching
        // published name is known to be unpublished, not just unverified.
        @Bean(destroyMethod = "close")
        McpToolInvoker toolInvoker() {
            return new McpToolInvoker(new McpProperties(), new SimpleMeterRegistry(), ObservationRegistry.NOOP, List.of());
        }
    }

    @Test
    void unpublishedAiToolWithoutAuthorizationRuleIsAccepted() {
        new ApplicationContextRunner().withUserConfiguration(EmptyInvokerConfig.class)
                .withBean(AiToolNotPublished.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableMethodSecurity
    static class PublishedAiToolInvokerConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }

        @Bean(destroyMethod = "close")
        McpToolInvoker toolInvoker() {
            McpToolInvoker invoker = new McpToolInvoker(new McpProperties(), new SimpleMeterRegistry(),
                    ObservationRegistry.NOOP, List.of());
            invoker.wrap(SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("published").description("exposed via MCP").build())
                    .callHandler((context, request) -> null)
                    .build());
            return invoker;
        }
    }

    @Test
    void publishedAiToolWithoutAuthorizationRuleFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(PublishedAiToolInvokerConfig.class)
                .withBean(AiToolPublished.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage()).contains("'published'");
                });
    }

    static class InstantiationCounter {
        static int count = 0;

        InstantiationCounter() {
            count++;
        }
    }

    interface PrototypeToolApi {
        String call();
    }

    static class UnprotectedPrototypeToolImpl extends InstantiationCounter implements PrototypeToolApi {
        @McpTool(name = "prototypeUnprotected", description = "no rule")
        @Override
        public String call() {
            return "x";
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class PrototypeInterfaceBeanConfig {
        @Bean
        static McpToolAuthorizationValidator validator(ConfigurableListableBeanFactory bf, ObjectProvider<McpToolInvoker> toolInvoker) {
            return new McpToolAuthorizationValidator(bf, toolInvoker);
        }

        // Declared return type is the interface, like the @Lazy case above, but prototype-scoped this time:
        // creating (and immediately discarding) an instance just to inspect its class would be a needless side
        // effect of the check itself, unlike forcing a genuinely lazy singleton.
        @Bean
        @Scope("prototype")
        PrototypeToolApi prototypeTool() {
            return new UnprotectedPrototypeToolImpl();
        }
    }

    @Test
    void prototypeBeanIsNotEagerlyInstantiatedForTheCheck() {
        InstantiationCounter.count = 0;
        new ApplicationContextRunner().withUserConfiguration(PrototypeInterfaceBeanConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(InstantiationCounter.count).isZero();
                });
    }
}
