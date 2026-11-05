package cn.byteforce.seedj.eventbus.wiring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * EventBus 装配配置（{@code seedj.eventbus.*}）。
 *
 * <p>队列与 outbox 的选择在各自模块里：
 * {@code seedj.eventbus.transport=file|memory}（seedj-infra-local）、
 * {@code seedj.outbox.enabled=true|false}（seedj-jdbc-postgresql）。
 */
@ConfigurationProperties(prefix = "seedj.eventbus")
public class EventBusProperties {

    /** 是否启用 EventBus（{@code EventPublisher} + consumer）。 */
    private boolean enabled = true;

    private final Consumer consumer = new Consumer();

    /** 额外需要确保存在的 topic（订阅 topic 会自动加入）。 */
    private List<String> topics = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
    }

    /**
     * 消费侧配置。
     */
    public static class Consumer {

        /** 是否启用消费者容器。 */
        private boolean enabled = true;

        /** 拉取间隔。 */
        private Duration pollInterval = Duration.ofMillis(200);

        /** 每次最多拉取条数。 */
        private int batchSize = 100;

        /** 投递租约；处理超过该时长且未续租时会被其他实例重新领取。 */
        private Duration lease = Duration.ofSeconds(30);

        /** 单条消息最大投递次数，超过后进入死信。 */
        private int maxAttempts = 5;

        /** 重试退避基数。 */
        private Duration retryBase = Duration.ofSeconds(1);

        /** 重试退避上限。 */
        private Duration retryMax = Duration.ofSeconds(300);

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

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
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
    }
}
