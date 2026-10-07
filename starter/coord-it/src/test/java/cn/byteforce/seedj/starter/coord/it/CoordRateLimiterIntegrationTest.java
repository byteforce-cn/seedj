package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import cn.byteforce.seedj.starter.coord.ratelimiter.CoordRateLimit;
import cn.byteforce.seedj.starter.coord.ratelimiter.CoordRateLimitExceededException;
import cn.byteforce.seedj.starter.coord.ratelimiter.CoordRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 限流封装集成验证（Phase 2，C4）：专用 API 判定面 + 注解 AOP 的
 * 「不静默」语义（放行则执行；拒绝则抛 {@link CoordRateLimitExceededException}）。
 *
 * <p>契约无规则下发面：本套件不假设具体配额（Agent 侧配置决定），只验证判定
 * 形状与 AOP 行为边界。</p>
 */
@SpringBootTest(properties = {
        "spring.application.name=it-ratelimiter"
})
class CoordRateLimiterIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private CoordRateLimiter rateLimiter;

    @Autowired
    private RateLimitedProbe probe;

    @Test
    void decisionContractHolds() {
        RateLimiterClient.RateLimitDecision decision = rateLimiter.acquire(uniqueName("it.rl"), 1);

        assertThat(decision).isNotNull();
        assertThat(decision.getRemaining()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aopEitherPassesOrFailsLoud() {
        Throwable thrown = catchThrowable(() -> probe.call("it.rl.aop"));

        if (thrown == null) {
            // 放行路径：方法体执行
            assertThat(probe.lastResult()).isEqualTo("ok:it.rl.aop");
        } else {
            // 拒绝路径：显式异常，不静默吞掉
            assertThat(thrown).isInstanceOf(CoordRateLimitExceededException.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        RateLimitedProbe rateLimitedProbe() {
            return new RateLimitedProbe();
        }
    }

    /** 注解目标（需可代理：非 final 类、非 final 方法）。 */
    public static class RateLimitedProbe {

        private volatile String lastResult;

        @CoordRateLimit(key = "#name")
        public String call(String name) {
            lastResult = "ok:" + name;
            return lastResult;
        }

        public String lastResult() {
            return lastResult;
        }
    }
}
