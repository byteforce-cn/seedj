package cn.byteforce.seedj.starter.coord.observability;

import cn.byteforce.coord.sdk.spi.ObservabilityProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordObservabilityAutoConfiguration} 装配 / 回退测试。
 */
class CoordObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordObservabilityAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void registersBridgeByDefault() {
        runner.run(context -> {
            assertThat(context.getBeanProvider(CoordMicrometerObservability.class).getIfAvailable()).isNotNull();
            assertThat(context).hasSingleBean(CoordMicrometerObservability.class);
        });
    }

    @Test
    void backsOffGracefullyWithoutMeterRegistryBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeanProvider(CoordMicrometerObservability.class).getIfAvailable()).isNull();
                });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("byteforce.coord.observability.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(CoordMicrometerObservability.class));
    }

    @Test
    void backsOffWithoutMicrometer() {
        runner.withClassLoader(new FilteredClassLoader("io.micrometer")).run(context ->
                assertThat(context).doesNotHaveBean(CoordMicrometerObservability.class));
    }

    @Test
    void userProviderTakesPrecedence() {
        ObservabilityProvider custom = new ObservabilityProvider() {
        };
        runner.withBean(ObservabilityProvider.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(ObservabilityProvider.class);
            assertThat(context.getBean(ObservabilityProvider.class)).isSameAs(custom);
            assertThat(context.getBeanProvider(CoordMicrometerObservability.class).getIfAvailable()).isNull();
        });
    }
}
