package cn.byteforce.seedj.starter.coord.ratelimiter;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;

/**
 * 限流拒绝（{@link CoordRateLimit} 拦截）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordRateLimitExceededException extends CoordException {

    private static final long serialVersionUID = 1L;

    /**
     * @param key     限流键
     * @param permits 许可数
     */
    public CoordRateLimitExceededException(String key, int permits) {
        super(ErrorCode.RESOURCE_EXHAUSTED, "限流拒绝: key=" + key + ", permits=" + permits);
    }
}
