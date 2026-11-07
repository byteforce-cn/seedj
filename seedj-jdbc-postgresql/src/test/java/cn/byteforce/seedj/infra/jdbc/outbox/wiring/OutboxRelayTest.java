package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OutboxRelayTest {

    private final FakeOutboxStore store = new FakeOutboxStore();
    private final FakeOutboxSink sink = new FakeOutboxSink();
    private final CountingMetrics metrics = new CountingMetrics();
    private OutboxRelay relay;

    @AfterEach
    void close() {
        if (relay != null) {
            relay.close();
        }
    }

    @Test
    @DisplayName("领取后投递成功 → markSent")
    void relayMarksSent() throws Exception {
        store.enqueue("r1", record("m1"), Duration.ofSeconds(30));
        relay = relay(withSink());

        relay.relay();

        assertThat(store.sentLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(metrics.sentLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.sent).containsExactly("r1");
        assertThat(sink.delivered).hasSize(1);
        assertThat(metrics.sent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("可重试失败 → markFailed(retryable=true, 有退避)")
    void retryableFailureIsMarkedRetryable() throws Exception {
        store.enqueue("r1", record("m1"), Duration.ofSeconds(30));
        sink.behavior =
                record ->
                        CompletableFuture.failedFuture(
                                new OutboxException(OutboxErrorCode.DELIVERY_FAILED, "boom"));
        relay = relay(withSink());

        relay.relay();

        assertThat(store.failedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        FakeOutboxStore.FailureCall failure = store.failed.get(0);
        assertThat(failure.failure().retryable()).isTrue();
        assertThat(failure.delay()).isPositive();
        assertThat(metrics.failedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(metrics.failedRetryable.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("不可重试失败 → markFailed(retryable=false, 无退避)")
    void nonRetryableFailureIsMarkedNonRetryable() throws Exception {
        store.enqueue("r1", record("m1"), Duration.ofSeconds(30));
        sink.behavior =
                record ->
                        CompletableFuture.failedFuture(new IllegalArgumentException("bad record"));
        relay = relay(withSink());

        relay.relay();

        assertThat(store.failedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        FakeOutboxStore.FailureCall failure = store.failed.get(0);
        assertThat(failure.failure().retryable()).isFalse();
        assertThat(failure.delay()).isZero();
    }

    @Test
    @DisplayName("租约丢失后不再 markSent")
    void leaseLostSkipsMarkSent() throws Exception {
        store.renewalSupported = true;
        store.renewResult = false;
        store.enqueue("r1", record("m1"), Duration.ofMillis(300));

        CountDownLatch deliverStarted = new CountDownLatch(1);
        sink.behavior =
                record ->
                        CompletableFuture.runAsync(
                                () -> {
                                    deliverStarted.countDown();
                                    sleep(1_500);
                                });
        relay = relay(withSink());

        relay.relay();

        assertThat(deliverStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1_200);
        assertThat(store.sent).isEmpty();
        assertThat(metrics.leaseLost.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("没有 sink 时空转，不 claim")
    void idlesWithoutSink() {
        relay = relay(provider(null));

        relay.relay();

        assertThat(store.sent).isEmpty();
        assertThat(store.failed).isEmpty();
    }

    @Test
    @DisplayName("close 幂等")
    void closeIsIdempotent() {
        relay = relay(withSink());

        relay.close();
        relay.close();
    }

    private OutboxRelay relay(
            ObjectProvider<cn.byteforce.seedj.outbox.spi.OutboxSink> sinkProvider) {
        OutboxProperties.Relay properties = new OutboxProperties.Relay();
        properties.setPollInterval(Duration.ofHours(1));
        properties.setLease(Duration.ofSeconds(30));
        properties.setMaxConcurrency(4);
        OutboxRelay relay = new OutboxRelay(provider(store), sinkProvider, properties, metrics);
        relay.afterPropertiesSet();
        return relay;
    }

    private ObjectProvider<cn.byteforce.seedj.outbox.spi.OutboxSink> withSink() {
        return provider(sink);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static OutboxRecord record(String messageId) {
        return new OutboxRecord(
                messageId,
                "destination",
                "partition-1",
                "content",
                1,
                "payload".getBytes(StandardCharsets.UTF_8),
                null,
                java.time.Instant.now());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CountingMetrics implements OutboxMetrics {
        private final java.util.concurrent.atomic.AtomicInteger sent =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger failedRetryable =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger leaseLost =
                new java.util.concurrent.atomic.AtomicInteger();
        private final CountDownLatch failedLatch = new CountDownLatch(1);
        private final CountDownLatch sentLatch = new CountDownLatch(1);

        @Override
        public void claimed(int count) {}

        @Override
        public void sent() {
            sent.incrementAndGet();
            sentLatch.countDown();
        }

        @Override
        public void failed(boolean retryable) {
            if (retryable) {
                failedRetryable.incrementAndGet();
            }
            failedLatch.countDown();
        }

        @Override
        public void leaseLost() {
            leaseLost.incrementAndGet();
        }

        @Override
        public void claimFailed() {}
    }
}
