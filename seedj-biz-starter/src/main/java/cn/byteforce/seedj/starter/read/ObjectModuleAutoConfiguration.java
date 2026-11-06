package cn.byteforce.seedj.starter.read;

import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeContextHolder;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.WriteScopeProvider;
import cn.byteforce.seedj.module.ObjectModuleConfigurer;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import cn.byteforce.seedj.read.DataScopeResolver;
import cn.byteforce.seedj.read.ScopeResolvers;
import cn.byteforce.seedj.starter.cqrs.wiring.TemporalCommandAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 收集 {@link ObjectModuleConfigurer} 并冻结为 {@link ObjectModuleRegistry}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = TemporalCommandAutoConfiguration.class)
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

    /**
     * 写范围：与读路径共用同一套行范围贡献者。
     *
     * @param registry 模块注册表
     * @param scopes   行范围贡献者
     * @return 写范围提供者
     */
    @Bean
    @ConditionalOnMissingBean
    public WriteScopeProvider writeScopeProvider(ObjectModuleRegistry registry,
                                                 ObjectProvider<DataScopeResolver> scopes) {
        return (objectType, caller) -> {
            ScopeContext context = ScopeContextHolder.current().orElse(ScopeContext.empty());
            return ScopeResolvers.resolve(objectType, caller, context,
                    scopes.orderedStream().toList(), registry.require(objectType).schema());
        };
    }

    /**
     * 命令执行面注册表。
     *
     * @param registry    模块注册表
     * @param policy      命令授权策略
     * @param runtime     命令运行时
     * @param writeScopes 写范围
     * @return 命令注册表
     */
    @Bean
    @ConditionalOnBean({CommandRuntime.class, CommandPolicy.class})
    @ConditionalOnMissingBean
    public CommandCatalog commandCatalog(ObjectModuleRegistry registry, CommandPolicy policy,
                                         CommandRuntime runtime,
                                         WriteScopeProvider writeScopes) {
        return new CommandCatalog(registry.commands(), policy, runtime, writeScopes);
    }
}
