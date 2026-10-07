package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Coord 注册 / 发现自动配置（starter-coord-registry，Phase 1）。
 *
 * <p>前置：容器中存在 {@link CoordClient}（starter-coord 提供或使用方自建）。
 * 未引入 starter-coord 或 {@code byteforce.coord.enabled=false} 时整体优雅回退
 * （而非启动失败），与「模块可独立增删」的装配口径一致。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(CoordClient.class)
@AutoConfigureAfter(name = "cn.byteforce.seedj.starter.coord.CoordAutoConfiguration")
@ConditionalOnBean(CoordClient.class)
@ConditionalOnProperty(
        prefix = "byteforce.coord.registry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordRegistryProperties.class)
public class CoordRegistryAutoConfiguration {

    /**
     * 发现消费客户端（native API + TTL 缓存降级，C6）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CoordDiscoveryClient coordDiscoveryClient(CoordClient coordClient, CoordRegistryProperties properties) {
        return new CoordDiscoveryClient(coordClient.registry(), properties.getDiscovery());
    }

    /**
     * 自动注册 / 优雅注销（C1 / C5）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "byteforce.coord.registry",
            name = "auto-register",
            havingValue = "true",
            matchIfMissing = true
    )
    public CoordAutoRegistration coordAutoRegistration(CoordClient coordClient,
                                                       CoordRegistryProperties properties,
                                                       Environment environment,
                                                       ObjectProvider<CoordRegistrationListener> listeners) {
        return new CoordAutoRegistration(coordClient, properties, environment,
                listeners.orderedStream().toList());
    }

    /**
     * 非 web / 固定端口兜底：{@code ApplicationReadyEvent} 时若仍无端口，按
     * {@code server.port} 注册。
     */
    @Bean
    @ConditionalOnMissingBean(name = "coordRegistrationReadyListener")
    @ConditionalOnBean(CoordAutoRegistration.class)
    public ApplicationListener<ApplicationReadyEvent> coordRegistrationReadyListener(
            CoordAutoRegistration registration, Environment environment) {
        return event -> registration.onApplicationReady(environment);
    }

    /**
     * web 容器端口监听（隔离 {@code spring-boot-web-server} 可选依赖；
     * 两级嵌套：外层只放核心 {@code @ConditionalOnClass}）。
     *
     * <p>注意：成员配置类不受外层 REGISTER 阶段条件保护（外层被跳过时成员仍会评估），
     * 因此本嵌套类必须自身镜像全部前置条件：CoordClient 存在 + enabled + auto-register；
     * 不能依赖 bean 方法上的 {@code @ConditionalOnBean(CoordAutoRegistration)}（
     * 对内层成员类的评估顺序不可靠）。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.boot.web.server.context.WebServerInitializedEvent.class)
    @ConditionalOnBean(CoordClient.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.registry",
            name = {"enabled", "auto-register"},
            havingValue = "true",
            matchIfMissing = true
    )
    static class WebServerListenerConfiguration {

        /**
         * 内层：web 事件类与前置条件均已确认，注册端口监听器。
         */
        @Bean
        @ConditionalOnMissingBean
        CoordWebServerPortListener coordWebServerPortListener(CoordAutoRegistration registration) {
            return new CoordWebServerPortListener(registration);
        }
    }
}
