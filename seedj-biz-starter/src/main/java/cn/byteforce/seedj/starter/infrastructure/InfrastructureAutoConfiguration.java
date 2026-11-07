package cn.byteforce.seedj.starter.infrastructure;

import cn.byteforce.seedj.core.InfrastructureCapability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 基础设施生命周期装配。
 *
 * <p>所有 Spring 组装集中在 starter；实现模块只提供 {@link InfrastructureCapability}
 * bean，不自己决定何时执行迁移。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
public class InfrastructureAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InfrastructureInitializer infrastructureInitializer(
            ObjectProvider<InfrastructureCapability> capabilities) {
        return new InfrastructureInitializer(capabilities);
    }
}
