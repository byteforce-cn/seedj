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
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordAutoConfiguration} 装配、回退与健康检查测试。
 *
 * <p>全部用例不依赖真实 Agent：端口固定为 1（必然拒绝连接），且 SDK 连接保持惰性，
 * 容器可正常启动 —— 这本身也是对「启动不硬连 Agent」契约的回归验证。</p>
 */
class CoordAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordAutoConfiguration.class))
            .withPropertyValues(
                    "byteforce.coord.agent-port=1",
                    "byteforce.coord.request-timeout=200ms"
            );

    @Test
    void registersClientAndAllSubClients() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CoordClient.class);
            assertThat(context).hasSingleBean(Registry.class);
            assertThat(context).hasSingleBean(ConfigClient.class);
            assertThat(context).hasSingleBean(LockClient.class);
            assertThat(context).hasSingleBean(IdGenClient.class);
            assertThat(context).hasSingleBean(CacheClient.class);
            assertThat(context).hasSingleBean(MqClient.class);
            assertThat(context).hasSingleBean(TransitClient.class);
            assertThat(context).hasSingleBean(WorkflowClient.class);
            assertThat(context).hasSingleBean(PolicyClient.class);
            assertThat(context).hasSingleBean(PkiClient.class);
            assertThat(context).hasSingleBean(ObjectStoreClient.class);
            assertThat(context).hasSingleBean(LeaderElectionClient.class);
            assertThat(context).hasSingleBean(EventClient.class);
            assertThat(context).hasSingleBean(SchedulerClient.class);
            assertThat(context).hasSingleBean(CircuitBreakerClient.class);
            assertThat(context).hasSingleBean(RateLimiterClient.class);
            assertThat(context).hasSingleBean(FeatureFlagClient.class);
            assertThat(context).hasBean("coordStartupAwaiter");
        });
    }

    @Test
    void bindsPropertiesIntoContext() {
        runner.withPropertyValues(
                        "byteforce.coord.auto-restore-watches=false",
                        "byteforce.coord.heartbeat-threads=2"
                )
                .run(context -> {
                    CoordProperties properties = context.getBean(CoordProperties.class);
                    assertThat(properties.isAutoRestoreWatches()).isFalse();
                    assertThat(properties.getHeartbeatThreads()).isEqualTo(2);
                });
    }

    @Test
    void disabledByEnabledProperty() {
        runner.withPropertyValues("byteforce.coord.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordClient.class);
            assertThat(context).doesNotHaveBean(LockClient.class);
            assertThat(context.containsBean("coordStartupAwaiter")).isFalse();
        });
    }

    @Test
    void backsOffWhenUserProvidesCoordClient() {
        runner.withUserConfiguration(UserClientConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(CoordClient.class);
            assertThat(context.getBean(CoordClient.class))
                    .isSameAs(UserClientConfiguration.USER_CLIENT);
            assertThat(context).hasSingleBean(LockClient.class);
        });
    }

    @Test
    void healthIndicatorRegisteredWhenHealthApiPresent() {
        runner.run(context -> assertThat(context).hasSingleBean(CoordHealthIndicator.class));
    }

    @Test
    void healthIndicatorHonoursStandardProperty() {
        runner.withPropertyValues("management.health.coord.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(CoordHealthIndicator.class));
    }

    @Test
    void healthConfigurationSkippedWhenHealthApiAbsent() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.boot.health"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CoordHealthIndicator.class);
                    assertThat(context).hasSingleBean(CoordClient.class);
                });
    }

    @Test
    void healthReportsDownWhenAgentUnreachable() {
        runner.run(context -> {
            Health health = context.getBean(CoordHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserClientConfiguration {

        static final CoordClient USER_CLIENT = CoordClient.create(CoordConfig.builder()
                .agentHost("127.0.0.1")
                .agentPort(1)
                .requestTimeout(Duration.ofMillis(200))
                .build());

        @Bean(destroyMethod = "close")
        CoordClient userCoordClient() {
            return USER_CLIENT;
        }
    }
}
