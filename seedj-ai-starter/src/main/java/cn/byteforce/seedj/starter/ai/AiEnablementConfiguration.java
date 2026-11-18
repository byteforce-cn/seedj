package cn.byteforce.seedj.starter.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 只产 {@link AiEnabledMarker}。
 *
 * <p>本类只能由 {@code @EnableAIServer} 的 {@code @Import} 引入，<b>不要</b>登记到 {@code
 * AutoConfiguration.imports}：清单的语义是「classpath 上有就生效」，而标记必须「注解写了才生效」。本模块被 {@code
 * seedj-it} 依赖，一旦登记进清单，IAM/IM/HR 的上下文都会拿到标记，能力自动配置（默认开启的 guard）便会误拦宿主的命令端点。
 * {@code seedj.ai.enabled=false} 时连标记也不产，所有能力随之消失。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "seedj.ai",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AiEnablementConfiguration {

    /**
     * 注册启用标记。
     *
     * @return 标记实例；能力自动配置用 {@code @ConditionalOnBean(AiEnabledMarker.class)} 门控
     */
    @Bean
    @ConditionalOnMissingBean
    AiEnabledMarker aiEnabledMarker() {
        return new AiEnabledMarker();
    }
}
