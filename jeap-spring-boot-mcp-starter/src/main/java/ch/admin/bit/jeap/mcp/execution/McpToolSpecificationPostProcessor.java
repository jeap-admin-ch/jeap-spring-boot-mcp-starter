package ch.admin.bit.jeap.mcp.execution;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.List;

/**
 * Routes every registered synchronous stateless tool specification through the {@link McpToolInvoker}.
 */
class McpToolSpecificationPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<McpToolInvoker> invoker;

    McpToolSpecificationPostProcessor(ObjectProvider<McpToolInvoker> invoker) {
        this.invoker = invoker;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(SyncToolSpecification.class::isInstance)) {
            McpToolInvoker toolInvoker = invoker.getObject();
            return list.stream().map(SyncToolSpecification.class::cast).map(toolInvoker::wrap).toList();
        }
        return bean;
    }
}
