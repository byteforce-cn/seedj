package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import java.time.Duration;

/**
 * Outbox relay 的指标 seam：有 micrometer 时由装配层注入真实实现，否则用 no-op。
 *
 * <p>这样 relay 不依赖任何指标库，也不会因为类路径缺 micrometer 而启动失败。
 */
public interface OutboxMetrics {

    void claimed(int count);

    void sent();

    void failed(boolean retryable);

    void leaseLost();

    void claimFailed();

    /** 本轮 claim 到的最老记录的库龄（occurredAt → now）。 */
    default void oldestLag(Duration lag) {
    }

    static OutboxMetrics noop() {
        return new OutboxMetrics() {
            @Override
            public void claimed(int count) {
            }

            @Override
            public void sent() {
            }

            @Override
            public void failed(boolean retryable) {
            }

            @Override
            public void leaseLost() {
            }

            @Override
            public void claimFailed() {
            }
        };
    }
}
