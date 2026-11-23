package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.infra.coord.CoordAutoConfiguration;
import cn.byteforce.seedj.infra.coord.CoordProperties;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * coord 工作流适配器装配。
 *
 * <p>{@code seedj.workflow.provider=coord} 时装配 bean {@code coord}；{@code seedj.coord.enabled!=true}
 * 由 {@code CoordProviderGuard} 启动失败（已有的守卫，无需改）。业务可用自己的 {@link WorkflowProvider} bean
 * 覆盖（{@code @ConditionalOnMissingBean}）。
 */
@AutoConfiguration(after = CoordAutoConfiguration.class)
@AutoConfigureBefore(name = "cn.byteforce.seedj.infra.local.workflow.WorkflowAutoConfiguration")
@ConditionalOnProperty(
        prefix = "seedj.workflow",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(prefix = "seedj.workflow", name = "provider", havingValue = "coord")
@EnableConfigurationProperties(CoordWorkflowProperties.class)
public class CoordWorkflowAutoConfiguration {

    @Bean(name = "coord")
    @ConditionalOnMissingBean(WorkflowProvider.class)
    CoordWorkflowProvider coordWorkflowProvider(
            CoordClient client,
            CoordProperties coord,
            CoordWorkflowProperties properties,
            Environment environment) {
        return new CoordWorkflowProvider(
                client.workflow(), client.kv(), properties, coord.resolveNamespace(environment));
    }
}
