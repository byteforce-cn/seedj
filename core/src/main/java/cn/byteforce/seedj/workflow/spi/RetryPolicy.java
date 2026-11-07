package cn.byteforce.seedj.workflow.spi;

import java.time.Duration;

/** 步骤级重试策略；退避由 Provider 按其时钟执行。 */
public record RetryPolicy(
        int maxAttempts, Duration initialBackoff, Duration maxBackoff, double multiplier) {
    public RetryPolicy {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be > 0");
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero())
            throw new IllegalArgumentException("initialBackoff must be positive");
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0)
            throw new IllegalArgumentException("maxBackoff must be >= initialBackoff");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1.0);
    }
}
