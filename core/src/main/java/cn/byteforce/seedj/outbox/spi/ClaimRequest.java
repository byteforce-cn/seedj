package cn.byteforce.seedj.outbox.spi;

import java.time.Duration;

/** 一次领取请求。 */
public record ClaimRequest(int limit, Duration lease) {

    public ClaimRequest {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit > 0");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
    }
}
