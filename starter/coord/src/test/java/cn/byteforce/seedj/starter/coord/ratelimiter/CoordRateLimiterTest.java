package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordRateLimiter} 专用 API 测试（C4：只做「按 key 请求许可」，不做配额管理）。
 */
class CoordRateLimiterTest {

    private final RateLimiterClient client = mock(RateLimiterClient.class);
    private final CoordRateLimiter rateLimiter = new CoordRateLimiter(client);

    @Test
    void tryAcquireReturnsDecisionFlag() {
        when(client.allow("k1", 1)).thenReturn(new RateLimiterClient.RateLimitDecision(true, 9, 1234));
        when(client.allow("k2", 2)).thenReturn(new RateLimiterClient.RateLimitDecision(false, 0, 1234));

        assertThat(rateLimiter.tryAcquire("k1", 1)).isTrue();
        assertThat(rateLimiter.tryAcquire("k2", 2)).isFalse();
    }

    @Test
    void acquireExposesFullDecision() {
        when(client.allow("k1", 3)).thenReturn(new RateLimiterClient.RateLimitDecision(true, 7, 999));

        RateLimiterClient.RateLimitDecision decision = rateLimiter.acquire("k1", 3);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.getRemaining()).isEqualTo(7);
        assertThat(decision.getResetTime()).isEqualTo(999);
        verify(client).allow("k1", 3);
    }
}
