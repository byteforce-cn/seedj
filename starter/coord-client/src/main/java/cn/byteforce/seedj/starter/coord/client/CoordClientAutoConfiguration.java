package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Coord 客户端负载均衡 / 声明式 HTTP 自动配置（starter-coord-client，Phase 3）。
 *
 * <p>前置：registry 模块提供 {@link CoordDiscoveryClient}（实例来源 + 缓存降级）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(CoordClient.class)
@AutoConfigureAfter(name = "cn.byteforce.seedj.starter.coord.registry.CoordRegistryAutoConfiguration")
@ConditionalOnBean(CoordDiscoveryClient.class)
@ConditionalOnProperty(
        prefix = "byteforce.coord.client",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordClientProperties.class)
public class CoordClientAutoConfiguration {

    /**
     * 随机策略（显式配置 {@code byteforce.coord.client.load-balancer.strategy=random} 时）。
     */
    @Bean
    @ConditionalOnMissingBean(CoordLoadBalancer.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.client.load-balancer",
            name = "strategy",
            havingValue = "random"
    )
    public CoordLoadBalancer randomCoordLoadBalancer(CoordDiscoveryClient discoveryClient) {
        return new RandomCoordLoadBalancer(discoveryClient);
    }

    /**
     * 轮询策略（默认；C6）。
     */
    @Bean
    @ConditionalOnMissingBean(CoordLoadBalancer.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.client.load-balancer",
            name = "strategy",
            havingValue = "round-robin",
            matchIfMissing = true
    )
    public CoordLoadBalancer roundRobinCoordLoadBalancer(CoordDiscoveryClient discoveryClient) {
        return new RoundRobinCoordLoadBalancer(discoveryClient);
    }

    /**
     * 熔断状态观察桥（可选；默认关闭——coord 熔断器为 Agent 本地状态，仅作协调观测）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "byteforce.coord.client.circuit-breaker-report",
            name = "enabled",
            havingValue = "true"
    )
    public CoordCircuitBreakerReporter coordCircuitBreakerReporter(CoordClient coordClient) {
        return new CoordCircuitBreakerReporter(coordClient.circuitBreaker());
    }

    /**
     * 声明式 HTTP 客户端工厂（RestClient 形态）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CoordHttpServiceClientFactory coordHttpServiceClientFactory(
            CoordLoadBalancer loadBalancer, ObjectProvider<CoordCircuitBreakerReporter> reporter) {
        return new CoordHttpServiceClientFactory(loadBalancer, reporter.getIfAvailable());
    }

    /**
     * WebClient 形态（隔离 spring-webflux 可选依赖；镜像全部前置条件）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.web.reactive.function.client.WebClient.class)
    @ConditionalOnBean(CoordDiscoveryClient.class)
    @ConditionalOnProperty(
            prefix = "byteforce.coord.client",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    static class ReactiveConfiguration {

        /**
         * 声明式 HTTP 客户端工厂（WebClient 形态）。
         */
        @Bean
        @ConditionalOnMissingBean
        CoordReactiveHttpServiceClientFactory coordReactiveHttpServiceClientFactory(
                CoordLoadBalancer loadBalancer, ObjectProvider<CoordCircuitBreakerReporter> reporter) {
            return new CoordReactiveHttpServiceClientFactory(loadBalancer, reporter.getIfAvailable());
        }
    }
}
