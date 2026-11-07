package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 装配配置（{@code seedj.outbox.*}）。
 *
 * <p>存储实现的选择在实现模块：{@code seedj.outbox.provider=postgres} （seedj-jdbc-postgresql）。
 */
@ConfigurationProperties(prefix = "seedj.outbox")
public class OutboxProperties {

    /** 是否启用 outbox 能力（writer + relay）。 */
    private boolean enabled = true;

    private final Relay relay = new Relay();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Relay getRelay() {
        return relay;
    }

    public static class Relay {

        /** 是否启用 outbox relay。没有 store 或 sink 时自动空转并打一条 info 日志。 */
        private boolean enabled = true;

        /** 轮询间隔。 */
        private Duration pollInterval = Duration.ofSeconds(1);

        /** 每批最多领取条数。 */
        private int batchSize = 100;

        /** 租约时长；长投递场景由心跳续租。 */
        private Duration lease = Duration.ofSeconds(30);

        /** 并发投递上限。 */
        private int maxConcurrency = 20;

        /** 重试退避基数。 */
        private Duration retryBase = Duration.ofSeconds(1);

        /** 重试退避上限。 */
        private Duration retryMax = Duration.ofSeconds(300);

        /** 连续续租失败多少次判定租约丢失。 */
        private int leaseRenewFailures = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public Duration getRetryBase() {
            return retryBase;
        }

        public void setRetryBase(Duration retryBase) {
            this.retryBase = retryBase;
        }

        public Duration getRetryMax() {
            return retryMax;
        }

        public void setRetryMax(Duration retryMax) {
            this.retryMax = retryMax;
        }

        public int getLeaseRenewFailures() {
            return leaseRenewFailures;
        }

        public void setLeaseRenewFailures(int leaseRenewFailures) {
            this.leaseRenewFailures = leaseRenewFailures;
        }
    }
}
