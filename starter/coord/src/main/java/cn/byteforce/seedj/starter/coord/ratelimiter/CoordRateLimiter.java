package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.ratelimiter.RateLimiterClient;

/**
 * 限流专用 API（C4）：按实例（Agent 本地）令牌桶语义，**不承诺集群级限流**；
 * starter 不定义 / 不管理配额（配额由 Agent 侧配置 / 业务管理面维护，契约无规则下发面）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class CoordRateLimiter {

    private final RateLimiterClient rateLimiterClient;

    /**
     * @param rateLimiterClient SDK 限流客户端
     */
    public CoordRateLimiter(RateLimiterClient rateLimiterClient) {
        this.rateLimiterClient = rateLimiterClient;
    }

    /**
     * 申请许可。
     *
     * @param key     限流键（配额语义由上游定义）
     * @param permits 许可数（≥1）
     * @return 判定结果（含剩余量 / 重置时间，可用于观测）
     */
    public RateLimiterClient.RateLimitDecision acquire(String key, int permits) {
        return rateLimiterClient.allow(key, permits);
    }

    /**
     * 申请许可（布尔视图）。
     *
     * @param key     限流键
     * @param permits 许可数
     * @return true 表示放行
     */
    public boolean tryAcquire(String key, int permits) {
        return rateLimiterClient.allow(key, permits).isAllowed();
    }
}
