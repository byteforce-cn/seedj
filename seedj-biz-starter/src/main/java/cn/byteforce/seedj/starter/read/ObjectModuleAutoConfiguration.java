package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 收集 {@link ObjectModuleConfigurer} 并冻结为 {@link ObjectModuleRegistry}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
public class ObjectModuleAutoConfiguration {

    /**
     * 冻结后的模块注册表。
     *
     * @param configurers 实体声明
     * @return 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectModuleRegistry objectModuleRegistry(
            ObjectProvider<ObjectModuleConfigurer> configurers) {
        ObjectModules modules = ObjectModules.create();
        configurers.orderedStream().forEach(configurer -> configurer.configure(modules));
        return modules.freeze();
    }
}
