package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CoordRateLimiterAutoConfiguration} 装配 / 回退测试。
 */
class CoordRateLimiterAutoConfigurationTest {

    private final CoordClient coordClient = mock(CoordClient.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoordRateLimiterAutoConfiguration.class))
            .withBean(CoordClient.class, () -> {
                when(coordClient.rateLimiter()).thenReturn(mock(RateLimiterClient.class));
                return coordClient;
            });

    @Test
    void registersLimiterAndAdvisorByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CoordRateLimiter.class);
            assertThat(context).hasBean("coordRateLimitAdvisor");
        });
    }

    @Test
    void backsOffWhenDisabled() {
        runner.withPropertyValues("byteforce.coord.ratelimiter.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(CoordRateLimiter.class);
            assertThat(context).doesNotHaveBean("coordRateLimitAdvisor");
        });
    }

    @Test
    void aopDisabledKeepsApi() {
        runner.withPropertyValues("byteforce.coord.ratelimiter.aop-enabled=false").run(context -> {
            assertThat(context).hasSingleBean(CoordRateLimiter.class);
            assertThat(context).doesNotHaveBean("coordRateLimitAdvisor");
        });
    }

    @Test
    void backsOffWithoutCoordClient() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoordRateLimiterAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CoordRateLimiter.class));
    }
}
