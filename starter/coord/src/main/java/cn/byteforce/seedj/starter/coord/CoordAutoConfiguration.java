package cn.byteforce.seedj.starter.coord;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.CoordConfig;
import cn.byteforce.coord.sdk.cache.CacheClient;
import cn.byteforce.coord.sdk.circuitbreaker.CircuitBreakerClient;
import cn.byteforce.coord.sdk.config.ConfigClient;
import cn.byteforce.coord.sdk.election.LeaderElectionClient;
import cn.byteforce.coord.sdk.event.EventClient;
import cn.byteforce.coord.sdk.featureflags.FeatureFlagClient;
import cn.byteforce.coord.sdk.idgen.IdGenClient;
import cn.byteforce.coord.sdk.lock.LockClient;
import cn.byteforce.coord.sdk.mq.MqClient;
import cn.byteforce.coord.sdk.objectstore.ObjectStoreClient;
import cn.byteforce.coord.sdk.pki.PkiClient;
import cn.byteforce.coord.sdk.policy.PolicyClient;
import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.scheduler.SchedulerClient;
import cn.byteforce.coord.sdk.spi.ObservabilityProvider;
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Coord 自动配置：把 {@code coord-java-sdk} 接线为 Spring 基础设施。
 *
 * <p>coord 上游明确不提供官方 Spring Boot starter（产品决策），官方 recipe 只有一条：
 * {@code @Bean(destroyMethod = "close")} + {@code CoordClient.create(CoordConfig)}。
 * 本自动配置把该 recipe 固化为消费侧能力：</p>
 * <ul>
 *   <li>属性外部化：{@link CoordProperties}（前缀 {@code byteforce.coord}）；</li>
 *   <li>生命周期托管：{@code close()} 注销注册、取消 watch/MQ 订阅、关闭连接与线程池；</li>
 *   <li>全部子客户端以独立 Bean 暴露，可直接按类型注入；</li>
 *   <li>可选健康检查（classpath 存在 Actuator 健康 SPI 时）；</li>
 *   <li>可选启动等待与协议协商（{@code await-connected-timeout}，fail-fast）。</li>
 * </ul>
 *
 * <p>所有 Bean 均为 {@link ConditionalOnMissingBean}，使用方定义同类型 Bean 即可接管。
 * 连接保持惰性：容器启动不要求 Agent 在线，首次 RPC（或健康探测）时才建链。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass(CoordClient.class)
@ConditionalOnProperty(
        prefix = "byteforce.coord",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordProperties.class)
public class CoordAutoConfiguration {

    /**
     * Coord 主客户端。
     *
     * <p>{@code destroyMethod = "close"} 是上游 recipe 的生命周期钩子：容器关闭时依次
     * 注销注册、取消 Watch / MQ 订阅、关闭 gRPC channel 与线程池。</p>
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public CoordClient coordClient(CoordProperties properties,
                                   ObjectProvider<CoordAuthTokenSupplier> authTokenSupplier,
                                   ObjectProvider<ObservabilityProvider> observabilityProvider) {
        CoordConfig.Builder builder = properties.toBuilder();
        CoordAuthTokenSupplier tokenSupplier = authTokenSupplier.getIfAvailable();
        if (tokenSupplier != null) {
            builder.authTokenSupplier(tokenSupplier);
        } else if (StringUtils.hasText(properties.getAuthToken())) {
            builder.authToken(properties.getAuthToken());
        }
        ObservabilityProvider observability = observabilityProvider.getIfAvailable();
        if (observability != null) {
            builder.observabilityProvider(observability);
        }
        return CoordClient.create(builder.build());
    }

    /**
     * 可选启动等待：{@code byteforce.coord.await-connected-timeout > 0} 时，启动阶段
     * 等待 Agent 可达并完成协议协商；失败抛 {@code CoordException} 阻止启动（fail-fast）。
     * 未设置时不做任何事（连接保持惰性，首次 RPC 时协商）。
     */
    @Bean
    public ApplicationRunner coordStartupAwaiter(CoordClient coordClient, CoordProperties properties) {
        return args -> {
            Duration timeout = properties.getAwaitConnectedTimeout();
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                coordClient.awaitConnected(timeout);
            }
        };
    }

    /**
     * 服务注册与发现。
     */
    @Bean
    @ConditionalOnMissingBean
    public Registry registry(CoordClient coordClient) {
        return coordClient.registry();
    }

    /**
     * 动态配置（含 Watch 订阅）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ConfigClient configClient(CoordClient coordClient) {
        return coordClient.configClient();
    }

    /**
     * 分布式互斥锁。
     */
    @Bean
    @ConditionalOnMissingBean
    public LockClient lockClient(CoordClient coordClient) {
        return coordClient.lock();
    }

    /**
     * 分布式唯一 ID 生成。
     */
    @Bean
    @ConditionalOnMissingBean
    public IdGenClient idGenClient(CoordClient coordClient) {
        return coordClient.idgen();
    }

    /**
     * 分布式缓存（String / Hash / List / Set）。
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheClient cacheClient(CoordClient coordClient) {
        return coordClient.cache();
    }

    /**
     * 消息队列（topic 管理、publish、poll + ack、DLQ）。
     */
    @Bean
    @ConditionalOnMissingBean
    public MqClient mqClient(CoordClient coordClient) {
        return coordClient.mq();
    }

    /**
     * 信封加密/解密。
     */
    @Bean
    @ConditionalOnMissingBean
    public TransitClient transitClient(CoordClient coordClient) {
        return coordClient.transit();
    }

    /**
     * 工作流定义与实例管理。
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowClient workflowClient(CoordClient coordClient) {
        return coordClient.workflow();
    }

    /**
     * RBAC/ABAC 策略评估。
     */
    @Bean
    @ConditionalOnMissingBean
    public PolicyClient policyClient(CoordClient coordClient) {
        return coordClient.policy();
    }

    /**
     * 本地 PKI CA 证书操作。
     */
    @Bean
    @ConditionalOnMissingBean
    public PkiClient pkiClient(CoordClient coordClient) {
        return coordClient.pki();
    }

    /**
     * 对象存储（EXPERIMENTAL）。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectStoreClient objectStoreClient(CoordClient coordClient) {
        return coordClient.objectStore();
    }

    /**
     * 租约式 Leader 选举。
     */
    @Bean
    @ConditionalOnMissingBean
    public LeaderElectionClient leaderElectionClient(CoordClient coordClient) {
        return coordClient.election();
    }

    /**
     * 发布/订阅事件通知。
     */
    @Bean
    @ConditionalOnMissingBean
    public EventClient eventClient(CoordClient coordClient) {
        return coordClient.events();
    }

    /**
     * 分布式作业调度（register/claim/complete）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SchedulerClient schedulerClient(CoordClient coordClient) {
        return coordClient.scheduler();
    }

    /**
     * Agent 本地熔断器状态。
     */
    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerClient circuitBreakerClient(CoordClient coordClient) {
        return coordClient.circuitBreaker();
    }

    /**
     * Agent 本地令牌桶限流。
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimiterClient rateLimiterClient(CoordClient coordClient) {
        return coordClient.rateLimiter();
    }

    /**
     * 功能开关评估（只读）。
     */
    @Bean
    @ConditionalOnMissingBean
    public FeatureFlagClient featureFlagClient(CoordClient coordClient) {
        return coordClient.featureFlags();
    }

    /**
     * 健康检查（可选）：classpath 存在 Actuator 健康 SPI（spring-boot-health）时生效。
     *
     * <p>必须两级嵌套：外层只允许出现来自核心模块的条件（{@code @ConditionalOnClass}），
     * 当健康 SPI 缺失时整个外层被跳过，内层（引用 spring-boot-health 注解）不会被解析——
     * 否则条件收集阶段就会因找不到 {@code OnEnabledHealthIndicatorCondition} 而启动失败。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.boot.health.contributor.HealthIndicator.class)
    static class HealthConfiguration {

        /**
         * 内层：健康 SPI 已确认存在后，再遵循标准开关
         * {@code management.health.coord.enabled}（默认开启）。
         */
        @Configuration(proxyBeanMethods = false)
        @org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator("coord")
        static class EnabledHealthIndicatorConfiguration {

            /**
             * bean 名固定为 {@code coordHealthIndicator}，定义同名 Bean 即可接管。
             */
            @Bean
            @ConditionalOnMissingBean(name = "coordHealthIndicator")
            org.springframework.boot.health.contributor.HealthIndicator coordHealthIndicator(CoordClient coordClient) {
                return new CoordHealthIndicator(coordClient);
            }
        }
    }
}
