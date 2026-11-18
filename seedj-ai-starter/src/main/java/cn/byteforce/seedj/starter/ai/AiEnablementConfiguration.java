package cn.byteforce.seedj.starter.ai;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 只产 {@link AiEnabledMarker}。
 *
 * <p>由 {@code @EnableAIServer} 通过 {@code @Import} 引入，<b>不</b>登记在 {@code
 * AutoConfiguration.imports} 里：本模块被 {@code seedj-it} 依赖，若标记由自动配置清单发现，每个 classpath
 * 上有本模块的应用都会拿到标记，能力自动配置（默认开启的 guard）便会误拦宿主的命令端点。{@code seedj.ai.enabled=false}
 * 时连标记也不产，所有能力随之消失。
 */
@AutoConfiguration
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
