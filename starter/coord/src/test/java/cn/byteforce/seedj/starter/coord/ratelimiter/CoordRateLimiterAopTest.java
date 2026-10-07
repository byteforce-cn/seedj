package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordRateLimit} 注解 AOP 语义测试：
 * 字面量 / SpEL key、放行 / 拒绝、开关回退；Advisor 由容器默认 AOP 设施应用。
 */
class CoordRateLimiterAopTest {

    private final RateLimiterClient rateLimiterClient = mock(RateLimiterClient.class);
    private final CoordClient coordClient = mock(CoordClient.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CoordRateLimiterAutoConfiguration.class, AopAutoConfiguration.class))
                .withUserConfiguration(ProbeBeans.class)
                .withBean(CoordClient.class, () -> {
                    when(coordClient.rateLimiter()).thenReturn(rateLimiterClient);
                    return coordClient;
                });
    }

    private void allowAll(boolean allowed) {
        when(rateLimiterClient.allow(anyString(), anyInt()))
                .thenAnswer(invocation -> new RateLimiterClient.RateLimitDecision(allowed, 1, 0));
    }

    @Test
    void allowedInvocationResolvesLiteralKeyAndProceeds() {
        allowAll(true);
        runner().run(context -> {
            RateLimitProbe probe = context.getBean(RateLimitProbe.class);

            assertThat(probe.literal()).isEqualTo("ok");
            verify(rateLimiterClient).allow("literal-key", 1);
        });
    }

    @Test
    void spelKeyResolvesMethodParameter() {
        allowAll(true);
        runner().run(context -> {
            RateLimitProbe probe = context.getBean(RateLimitProbe.class);

            assertThat(probe.spel("alice")).isEqualTo("ok:alice");
            verify(rateLimiterClient).allow("alice", 2);
        });
    }

    @Test
    void deniedInvocationThrowsAndSkipsBody() {
        allowAll(false);
        runner().run(context -> {
            RateLimitProbe probe = context.getBean(RateLimitProbe.class);

            assertThatThrownBy(() -> probe.spel("bob"))
                    .isInstanceOf(CoordRateLimitExceededException.class)
                    .hasMessageContaining("bob");
        });
    }

    @Test
    void aopCanBeDisabled() {
        allowAll(false);
        runner().withPropertyValues("byteforce.coord.ratelimiter.aop-enabled=false").run(context -> {
            RateLimitProbe probe = context.getBean(RateLimitProbe.class);

            assertThat(probe.spel("bob")).as("无 AOP 时直接放行且不请求许可").isEqualTo("ok:bob");
            verify(rateLimiterClient, never()).allow(anyString(), anyInt());
        });
    }

    @Test
    void unannotatedMethodIsNotIntercepted() {
        allowAll(false);
        runner().run(context -> {
            RateLimitProbe probe = context.getBean(RateLimitProbe.class);

            assertThat(probe.plain()).isEqualTo("plain");
            verify(rateLimiterClient, never()).allow(anyString(), anyInt());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeBeans {

        @Bean
        RateLimitProbe rateLimitProbe() {
            return new RateLimitProbe();
        }
    }

    /** 测试目标 Bean（普通类，可被 CGLIB 代理）。 */
    static class RateLimitProbe {

        @CoordRateLimit(key = "literal-key")
        String literal() {
            return "ok";
        }

        @CoordRateLimit(key = "#name", permits = 2)
        String spel(String name) {
            return "ok:" + name;
        }

        String plain() {
            return "plain";
        }
    }
}
