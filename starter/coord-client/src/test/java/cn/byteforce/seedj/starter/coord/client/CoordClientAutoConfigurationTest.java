package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.circuitbreaker.CircuitBreakerClient;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordRegistryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordClientAutoConfiguration} 装配 / 回退测试。
 */
class CoordClientAutoConfigurationTest {

    private final CoordClient coordClient = mock(CoordClient.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordClientAutoConfiguration.class))
                .withBean(CoordDiscoveryClient.class, () -> {
                    CoordRegistryProperties.Discovery discovery = new CoordRegistryProperties.Discovery();
                    discovery.setCacheEnabled(false);
                    return new CoordDiscoveryClient(mock(Registry.class), discovery);
                });
    }

    @Test
    void registersRoundRobinByDefault() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(CoordLoadBalancer.class);
            assertThat(context.getBean(CoordLoadBalancer.class))
                    .isInstanceOf(RoundRobinCoordLoadBalancer.class);
            assertThat(context).hasSingleBean(CoordHttpServiceClientFactory.class);
            assertThat(context).hasSingleBean(CoordReactiveHttpServiceClientFactory.class);
            assertThat(context).doesNotHaveBean(CoordCircuitBreakerReporter.class);
        });
    }

    @Test
    void honoursRandomStrategy() {
        runner().withPropertyValues("byteforce.coord.client.load-balancer.strategy=random").run(context -> {
            assertThat(context).hasSingleBean(CoordLoadBalancer.class);
            assertThat(context.getBean(CoordLoadBalancer.class)).isInstanceOf(RandomCoordLoadBalancer.class);
        });
    }

    @Test
    void backsOffWhenDisabled() {
        runner().withPropertyValues("byteforce.coord.client.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordLoadBalancer.class);
            assertThat(context).doesNotHaveBean(CoordHttpServiceClientFactory.class);
        });
    }

    @Test
    void backsOffWithoutDiscoveryClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordClientAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CoordLoadBalancer.class));
    }

    @Test
    void reactiveDisabledWithoutWebflux() {
        runner().withClassLoader(new FilteredClassLoader("org.springframework.web.reactive"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CoordReactiveHttpServiceClientFactory.class);
                    assertThat(context).hasSingleBean(CoordHttpServiceClientFactory.class);
                });
    }

    @Test
    void circuitBreakerReporterEnabledExplicitly() {
        when(coordClient.circuitBreaker()).thenReturn(mock(CircuitBreakerClient.class));
        runner().withBean(CoordClient.class, () -> coordClient)
                .withPropertyValues("byteforce.coord.client.circuit-breaker-report.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CoordCircuitBreakerReporter.class);
                    assertThat(context.getBean(CoordHttpServiceClientFactory.class)).isNotNull();
                });
    }
}
