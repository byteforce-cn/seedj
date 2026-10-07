package cn.byteforce.seedj.starter.coord.config;

import cn.byteforce.coord.sdk.CoordClient;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Coord 配置自动配置（starter-coord-config，Phase 1 / 1.5）。
 *
 * <p>启动期导入（{@code spring.config.import=coord:...}）由
 * {@code META-INF/spring.factories} 注册的 ConfigData resolver / loader 独立完成，
 * 不依赖本类；本类负责运行期刷新链路（可变属性源 → rebind → 事件）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(CoordClient.class)
@AutoConfigureAfter(name = "cn.byteforce.seedj.starter.coord.CoordAutoConfiguration")
@ConditionalOnProperty(
        prefix = "byteforce.coord.config",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordConfigProperties.class)
public class CoordConfigAutoConfiguration {

    /**
     * {@code @ConfigurationProperties} Bean 登记器。
     *
     * <p>必须是 static @Bean（BeanPostProcessor 需在常规 Bean 实例化前注册）。</p>
     */
    @Bean
    public static CoordRefreshableBeansPostProcessor coordRefreshableBeansPostProcessor() {
        return new CoordRefreshableBeansPostProcessor();
    }

    /**
     * rebind 执行器（R3：{@code @ConfigurationProperties} 级）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CoordConfigurationPropertiesRebinder coordConfigurationPropertiesRebinder(
            ConfigurableListableBeanFactory beanFactory, CoordRefreshableBeansPostProcessor postProcessor) {
        return new CoordConfigurationPropertiesRebinder(beanFactory, postProcessor);
    }

    /**
     * 运行期刷新（隔离前置条件：成员配置类镜像全部断言——见 registry 模块同款教训，
     * 外层 REGISTER 阶段条件不保护成员类）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(CoordClient.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.config.refresh",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class RefreshConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CoordConfigRefreshManager coordConfigRefreshManager(CoordClient coordClient,
                                                            CoordConfigurationPropertiesRebinder rebinder,
                                                            ConfigurableEnvironment environment,
                                                            ApplicationEventPublisher eventPublisher) {
            return new CoordConfigRefreshManager(coordClient.configClient(), rebinder, environment, eventPublisher);
        }
    }
}
