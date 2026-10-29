package ch.admin.bit.jeap.mcp.security;

import ch.admin.bit.jeap.mcp.execution.McpToolInvoker;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default-deny for MCP tools: fails the application startup if an exported {@link McpTool} or Spring AI
 * {@link Tool} method has no effective, enforceable {@link PreAuthorize} rule, neither on the method nor on its
 * class (including meta-annotations). An annotation-less tool would otherwise be callable by any authenticated
 * client. It also fails if method security is not enabled, because {@code @PreAuthorize} would then be silently
 * ignored, and if a tool is published (for example backed by a hand-built {@code ToolCallback} or
 * {@code SyncToolSpecification}) that isn't backed by either annotation at all, because then there is nothing here
 * to check.
 */
class McpToolAuthorizationValidator implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;
    private final ObjectProvider<McpToolInvoker> toolInvoker;

    McpToolAuthorizationValidator(ConfigurableListableBeanFactory beanFactory, ObjectProvider<McpToolInvoker> toolInvoker) {
        this.beanFactory = beanFactory;
        this.toolInvoker = toolInvoker;
    }

    /**
     * One {@code @McpTool}/{@code @Tool} method found while scanning. {@code mcpTool} is {@code false} for a
     * {@code @Tool} method, which -- unlike {@code @McpTool} -- is not necessarily published via MCP at all (Spring
     * AI's own annotation for a service's internal ChatClient/LLM tool calls too); whether it is relevant here then
     * depends on whether it was actually published, decided after the scan once that is known.
     */
    private record ToolFinding(String beanName, String toolName, String description, boolean mcpTool, boolean authorized) {
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<ToolFinding> findings = new ArrayList<>();
        Set<String> scannedToolNames = new HashSet<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, true, false)) {
            Class<?> beanType = resolveActualType(beanName);
            if (beanType != null) {
                collectFindings(beanName, ClassUtils.getUserClass(beanType), findings, scannedToolNames);
            }
        }

        McpToolInvoker invoker = toolInvoker.getIfAvailable();
        Set<String> publishedTools = invoker != null ? invoker.knownTools() : null;

        // @McpTool is MCP-only by definition, so it is always relevant here. @Tool is dual-purpose -- also used
        // for a service's own, non-MCP LLM tool calls -- so it is only treated as relevant once it is known to
        // actually be published; otherwise an internal-only @Tool method would wrongly have to carry
        // @PreAuthorize, which would then also be enforced on a call path that has nothing to do with MCP. When
        // there is no McpToolInvoker at all (for example a narrow unit test with no full application context),
        // there is no way to tell published from unpublished, so fall back to the stricter, unconditional check.
        List<ToolFinding> relevant = findings.stream()
                .filter(f -> f.mcpTool() || publishedTools == null || publishedTools.contains(f.toolName()))
                .toList();

        List<String> toolBeans = relevant.stream().map(ToolFinding::beanName).distinct().toList();
        if (!toolBeans.isEmpty() && !isMethodSecurityEnabled()) {
            throw new IllegalStateException("MCP tools are defined (" + String.join(", ", toolBeans) + ") but method security " +
                    "is not enabled, so @PreAuthorize would not be enforced. Add @EnableMethodSecurity to a configuration class.");
        }
        List<String> violations = relevant.stream().filter(f -> !f.authorized()).map(ToolFinding::description).toList();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("MCP tools without an effective, enforceable authorization rule are not " +
                    "allowed: " + String.join("; ", violations));
        }
        checkEveryPublishedToolWasScanned(scannedToolNames);
    }

    /**
     * {@link McpTool} and {@link Tool} methods are checked above by inspecting the bean that declares them. A tool
     * can also reach the server without such a method, for example a hand-built {@code ToolCallback} or
     * {@code SyncToolSpecification} bean; there is no annotation to check on those, so they must be rejected here
     * instead of silently shipping without any authorization check.
     */
    private void checkEveryPublishedToolWasScanned(Set<String> scannedToolNames) {
        McpToolInvoker invoker = toolInvoker.getIfAvailable();
        if (invoker == null) {
            return;
        }
        Set<String> unverifiable = new TreeSet<>(invoker.knownTools());
        unverifiable.removeAll(scannedToolNames);
        if (!unverifiable.isEmpty()) {
            throw new IllegalStateException("MCP tools " + unverifiable + " are published but not backed by an "
                    + "@McpTool or @Tool method, so their authorization cannot be verified. Expose them through "
                    + "such a method with an explicit @PreAuthorize rule instead.");
        }
    }

    /**
     * For a bean not yet instantiated, {@link ConfigurableListableBeanFactory#getType} falls back to the
     * declared/generic type of its factory method instead of the real class. If that declared type is an
     * interface, a {@code @McpTool} method only present on the actual implementation would stay invisible to
     * {@link #collectFindings}, silently bypassing this check. Force such beans to initialize so the real class is
     * used; beans already created, or whose declared type is already a concrete class, are left untouched.
     * <p>
     * Deliberately <strong>not</strong> skipped for singleton beans marked {@code @Lazy} (or made lazy globally via
     * {@code spring.main.lazy-initialization}): that is exactly the case this exists to catch, and a bean's own
     * laziness is not a substitute for an authorization check. Only a genuinely prototype-scoped bean is skipped,
     * since creating (and immediately discarding) an instance just to inspect its class would be its own, needless
     * side effect, and {@link #checkEveryPublishedToolWasScanned} still catches it later if it turns out to publish
     * a tool after all.
     */
    private Class<?> resolveActualType(String beanName) {
        Class<?> declaredType = beanFactory.getType(beanName, false);
        if (declaredType == null || !declaredType.isInterface() || beanFactory.containsSingleton(beanName)) {
            return declaredType;
        }
        if (!beanFactory.isSingleton(beanName)) {
            return declaredType;
        }
        try {
            return beanFactory.getBean(beanName).getClass();
        } catch (BeansException e) {
            return declaredType;
        }
    }

    /**
     * Spring Security registers its {@code @PreAuthorize} advisor under this fixed name when method security is
     * enabled. The advisor has no public bean type that could be looked up instead.
     */
    private boolean isMethodSecurityEnabled() {
        return beanFactory.containsBean("preAuthorizeAuthorizationAdvisor");
    }

    private static void collectFindings(String beanName, Class<?> beanClass, List<ToolFinding> findings, Set<String> scannedToolNames) {
        ReflectionUtils.doWithMethods(beanClass, method -> {
            McpTool mcpTool = AnnotatedElementUtils.findMergedAnnotation(method, McpTool.class);
            Tool tool = mcpTool == null ? AnnotatedElementUtils.findMergedAnnotation(method, Tool.class) : null;
            if (mcpTool == null && tool == null) {
                return;
            }
            String toolName = mcpTool != null
                    ? (mcpTool.name().isBlank() ? method.getName() : mcpTool.name())
                    : (tool.name().isBlank() ? method.getName() : tool.name());
            scannedToolNames.add(toolName);

            boolean enforceable = isEnforceable(beanClass, method);
            boolean authorized = enforceable && hasAuthorizationRule(beanClass, method);
            String description = enforceable
                    ? "tool '%s' (%s#%s)".formatted(toolName, beanClass.getName(), method.getName())
                    : ("tool '%s' (%s#%s) is final, on a final class, or not public, so a security proxy can never "
                       + "override it to enforce @PreAuthorize -- Spring AI would invoke it directly on the proxy "
                       + "instance instead").formatted(toolName, beanClass.getName(), method.getName());
            findings.add(new ToolFinding(beanName, toolName, description, mcpTool != null, authorized));
        }, ReflectionUtils.USER_DECLARED_METHODS);
    }

    /**
     * Method security and {@code McpToolErrorInterceptor} both work through CGLIB class proxies, which cannot
     * override a {@code final} method, a method on a {@code final} class, or (reliably) a non-public one. Spring AI
     * then invokes such a tool method directly on the proxy instance, bypassing every interceptor -- including the
     * authorization check -- regardless of whether {@code @PreAuthorize} is present.
     */
    private static boolean isEnforceable(Class<?> beanClass, Method method) {
        return !Modifier.isFinal(beanClass.getModifiers())
                && !Modifier.isFinal(method.getModifiers())
                && Modifier.isPublic(method.getModifiers());
    }

    private static boolean hasAuthorizationRule(Class<?> beanClass, Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(beanClass, PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), PreAuthorize.class);
    }
}
