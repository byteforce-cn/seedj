package cn.byteforce.seedj.workflow.memory;

import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * 工作流内存实现装配。
 *
 * <p>按 ADR-0002，不使用 {@code ProviderFactory}/{@code reflective provider lookup} 反射；
 * bean 名为 {@code memory}，业务覆盖时用 {@code @ConditionalOnMissingBean} 让位。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.workflow", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowAutoConfiguration {

    @Bean(name = "memory")
    @ConditionalOnMissingBean(WorkflowProvider.class)
    @ConditionalOnProperty(prefix = "seedj.workflow", name = "provider",
            havingValue = "memory", matchIfMissing = true)
    public InMemoryWorkflowProvider memoryWorkflowProvider(ObjectProvider<Clock> clock,
                                                           ObjectProvider<IdGenerator<String>> idGenerator) {
        return new InMemoryWorkflowProvider(clock.getIfAvailable(Clock::systemUTC),
                idGenerator.getIfAvailable());
    }
}
