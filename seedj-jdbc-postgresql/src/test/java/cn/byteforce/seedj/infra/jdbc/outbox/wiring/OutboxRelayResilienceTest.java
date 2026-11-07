package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.ClaimRequest;
import cn.byteforce.seedj.outbox.spi.ClaimToken;
import cn.byteforce.seedj.outbox.spi.ClaimedRecord;
import cn.byteforce.seedj.outbox.spi.OutboxFailure;
import cn.byteforce.seedj.outbox.spi.OutboxSink;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the relay's failure paths: claim errors, saturated concurrency, store
 * write-back errors, retry backoff and heartbeat renewal.
 */
class OutboxRelayResilienceTest {

    private final Metrics metrics = new Metrics();
    private final List<OutboxRelay> relays = new ArrayList<>();

    @AfterEach
    void closeRelays() {
        relays.forEach(OutboxRelay::close);
    }

    @Test
    void disabledRelayNeverClaims() {
        ScriptedStore store = new ScriptedStore();
        OutboxRelay relay = relay(store, record -> CompletableFuture.completedFuture(null),
                properties -> properties.setEnabled(false));

        relay.relay();

        assertThat(store.claimCalls.get()).isZero();
    }

    @Test
    void claimFailureIsCountedAndDoesNotEscape() {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> {
            throw new IllegalStateException("database down");
        };
        OutboxRelay relay = relay(store, record -> CompletableFuture.completedFuture(null),
                properties -> {
                });

        relay.relay();

        assertThat(store.claimCalls.get()).isEqualTo(1);
        assertThat(metrics.claimFailed.get()).isEqualTo(1);
        assertThat(metrics.claimed.get()).isZero();
    }

    @Test
    void emptyClaimIsANoOp() {
        ScriptedStore store = new ScriptedStore();
        OutboxRelay relay = relay(store, record -> CompletableFuture.completedFuture(null),
                properties -> {
                });

        relay.relay();

        assertThat(store.claimCalls.get()).isEqualTo(1);
        assertThat(metrics.claimed.get()).isZero();
        assertThat(metrics.oldestLags).isEmpty();
    }

    @Test
    void concurrentRoundSkipsWhileAnotherRoundHoldsTheRelay() throws Exception {
        ScriptedStore store = new ScriptedStore();
        CountDownLatch claimStarted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        store.claimBehavior = request -> {
            claimStarted.countDown();
            await(releaseClaim);
            return List.of();
        };
        OutboxRelay relay = relay(store, record -> CompletableFuture.completedFuture(null),
                properties -> {
                });

        Thread round = new Thread(relay::relay, "relay-round");
        round.start();
        assertThat(claimStarted.await(2, TimeUnit.SECONDS)).isTrue();

        relay.relay();

        releaseClaim.countDown();
        round.join(2_000);
        assertThat(store.claimCalls.get()).isEqualTo(1);
    }

    @Test
    void roundSkipsWhenAllConcurrencyPermitsAreInFlight() throws Exception {
        ScriptedStore store = new ScriptedStore();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        CountDownLatch delivered = new CountDownLatch(1);
        store.claimBehavior = request -> List.of(store.claimedRecord(1));
        OutboxRelay relay = relay(store, record -> {
            delivered.countDown();
            return pending;
        }, properties -> properties.setMaxConcurrency(1));

        relay.relay();
        assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();
        relay.relay();

        assertThat(store.claimCalls.get()).isEqualTo(1);
        pending.complete(null);
        assertThat(store.sentAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
    }

    @Test
    void extraClaimedRecordsStopWhenPermitsRunOut() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(1), store.claimedRecord(1));
        List<OutboxRecord> delivered = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        OutboxRelay relay = relay(store, record -> {
            delivered.add(record);
            return pending;
        }, properties -> properties.setMaxConcurrency(1));

        relay.relay();

        assertThat(delivered).hasSize(1);
        pending.complete(null);
        assertThat(store.sentAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
    }

    @Test
    void synchronousSinkFailureIsMarkedRetryableWithBackoff() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(1));
        OutboxRelay relay = relay(store, record -> {
            throw new IllegalStateException("sink exploded");
        }, properties -> {
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        ScriptedStore.FailureCall call = store.failed.get(0);
        assertThat(call.failure().errorType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(call.failure().errorMessage()).isEqualTo("sink exploded");
        assertThat(call.failure().retryable()).isTrue();
        assertThat(call.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(metrics.failedRetryable.get()).isEqualTo(1);
    }

    @Test
    void nonRetryableInfrastructureFailureHasNoBackoff() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(3));
        OutboxRelay relay = relay(store, record -> {
            throw new InfrastructureException(InfrastructureErrorCode.MIGRATION_FAILED, "bad");
        }, properties -> {
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        ScriptedStore.FailureCall call = store.failed.get(0);
        assertThat(call.failure().retryable()).isFalse();
        assertThat(call.delay()).isZero();
        assertThat(metrics.failedRetryable.get()).isZero();
    }

    @Test
    void retryableInfrastructureFailureKeepsItsBackoff() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(2));
        OutboxRelay relay = relay(store, record -> {
            throw new InfrastructureException(InfrastructureErrorCode.NOT_READY, "not yet");
        }, properties -> {
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        ScriptedStore.FailureCall call = store.failed.get(0);
        assertThat(call.failure().retryable()).isTrue();
        assertThat(call.delay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void retryDelayUsesBaseWhenNoAttemptWasRecorded() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(0));
        OutboxRelay relay = relay(store, record -> CompletableFuture.failedFuture(
                new IllegalStateException("transient")), properties -> {
            properties.setRetryBase(Duration.ofSeconds(2));
            properties.setRetryMax(Duration.ofSeconds(60));
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.failed.get(0).delay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void retryDelayIsCappedAtTheMaximum() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(3));
        OutboxRelay relay = relay(store, record -> CompletableFuture.failedFuture(
                new IllegalStateException("transient")), properties -> {
            properties.setRetryBase(Duration.ofSeconds(1));
            properties.setRetryMax(Duration.ofSeconds(3));
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.failed.get(0).delay()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void retryDelaySaturatesWhenMultiplicationOverflows() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.claimBehavior = request -> List.of(store.claimedRecord(2));
        OutboxRelay relay = relay(store, record -> CompletableFuture.failedFuture(
                new IllegalStateException("transient")), properties -> {
            properties.setRetryBase(Duration.ofMillis(Long.MAX_VALUE));
            properties.setRetryMax(Duration.ofSeconds(9));
        });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(store.failed.get(0).delay()).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void markSentFailureIsLoggedButNotCountedAsSent() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.markSentFails = true;
        store.claimBehavior = request -> List.of(store.claimedRecord(1));
        OutboxRelay relay = relay(store, record -> CompletableFuture.completedFuture(null),
                properties -> {
                });

        relay.relay();

        assertThat(store.sentAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(store.sent).isEmpty();
        assertThat(metrics.sent.get()).isZero();
    }

    @Test
    void markFailedFailureIsLoggedButNotCountedAsFailed() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.markFailedFails = true;
        store.claimBehavior = request -> List.of(store.claimedRecord(1));
        OutboxRelay relay = relay(store, record -> CompletableFuture.failedFuture(
                new IllegalStateException("boom")), properties -> {
                });

        relay.relay();

        assertThat(store.failedAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(metrics.failedRetryable.get()).isZero();
    }

    @Test
    void heartbeatRenewalFailuresMarkTheLeaseLost() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.renewalSupported = true;
        store.renewBehavior = token -> {
            throw new IllegalStateException("renew failed");
        };
        CompletableFuture<Void> pending = new CompletableFuture<>();
        store.claimBehavior = request -> List.of(store.claimedRecord(1, Duration.ofMillis(300)));
        OutboxRelay relay = relay(store, record -> pending,
                properties -> properties.setLeaseRenewFailures(1));

        relay.relay();

        assertThat(awaitTrue(() -> metrics.leaseLost.get() >= 1, 4_000)).isTrue();
        assertThat(store.sent).isEmpty();
        pending.complete(null);
        Thread.sleep(150);
    }

    @Test
    void successfulRenewalKeepsTheLease() throws Exception {
        ScriptedStore store = new ScriptedStore();
        store.renewalSupported = true;
        CompletableFuture<Void> pending = new CompletableFuture<>();
        store.claimBehavior = request -> List.of(store.claimedRecord(1, Duration.ofMillis(300)));
        OutboxRelay relay = relay(store, record -> pending, properties -> {
        });

        relay.relay();
        Thread.sleep(1_200);

        assertThat(store.renewals.get()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.leaseLost.get()).isZero();
        pending.complete(null);
        assertThat(store.sentAttempt.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
    }

    private OutboxRelay relay(ScriptedStore store, OutboxSink sink,
                              Consumer<OutboxProperties.Relay> customizer) {
        OutboxProperties.Relay properties = new OutboxProperties.Relay();
        properties.setPollInterval(Duration.ofHours(1));
        properties.setLease(Duration.ofSeconds(30));
        properties.setMaxConcurrency(4);
        customizer.accept(properties);
        OutboxRelay relay = new OutboxRelay(provider(store), provider(sink), properties, metrics);
        relay.afterPropertiesSet();
        relays.add(relay);
        return relay;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition, long millis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ScriptedStore implements OutboxStore {

        private final AtomicInteger recordSequence = new AtomicInteger();
        private final AtomicInteger claimCalls = new AtomicInteger();

        volatile Function<ClaimRequest, List<ClaimedRecord>> claimBehavior = request -> List.of();
        volatile Function<ClaimToken, Boolean> renewBehavior = token -> true;
        volatile boolean renewalSupported;
        volatile boolean markSentFails;
        volatile boolean markFailedFails;

        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<FailureCall> failed = new CopyOnWriteArrayList<>();
        final AtomicInteger renewals = new AtomicInteger();
        final CountDownLatch sentAttempt = new CountDownLatch(1);
        final CountDownLatch failedAttempt = new CountDownLatch(1);

        ClaimedRecord claimedRecord(int attempts) {
            return claimedRecord(attempts, Duration.ofSeconds(30));
        }

        ClaimedRecord claimedRecord(int attempts, Duration lease) {
            String id = "r-" + recordSequence.incrementAndGet();
            return new ClaimedRecord(id, new OutboxRecord(id + "-msg", "destination",
                    "partition-1", "content", 1, "payload".getBytes(StandardCharsets.UTF_8),
                    null, Instant.now()), attempts,
                    new ClaimToken(id, "lease-" + id, lease), Instant.now().plus(lease));
        }

        @Override
        public List<ClaimedRecord> claim(ClaimRequest request) {
            claimCalls.incrementAndGet();
            return claimBehavior.apply(request);
        }

        @Override
        public void append(OutboxRecord record) {
            throw new UnsupportedOperationException("append is not used by relay tests");
        }

        @Override
        public void markSent(ClaimToken token) {
            try {
                if (markSentFails) {
                    throw new IllegalStateException("markSent rejected");
                }
                sent.add(token.recordId());
            } finally {
                sentAttempt.countDown();
            }
        }

        @Override
        public void markFailed(ClaimToken token, OutboxFailure failure, Duration retryDelay) {
            try {
                if (markFailedFails) {
                    throw new IllegalStateException("markFailed rejected");
                }
                failed.add(new FailureCall(token, failure, retryDelay));
            } finally {
                failedAttempt.countDown();
            }
        }

        @Override
        public boolean supportsRenewal() {
            return renewalSupported;
        }

        @Override
        public boolean renewLease(ClaimToken token) {
            renewals.incrementAndGet();
            return renewBehavior.apply(token);
        }

        @Override
        public void retry(String recordId, String operator) {
        }

        @Override
        public void discard(String recordId, String operator) {
        }

        static final class FailureCall {
            private final ClaimToken token;
            private final OutboxFailure failure;
            private final Duration delay;

            FailureCall(ClaimToken token, OutboxFailure failure, Duration delay) {
                this.token = token;
                this.failure = failure;
                this.delay = delay;
            }

            ClaimToken token() {
                return token;
            }

            OutboxFailure failure() {
                return failure;
            }

            Duration delay() {
                return delay;
            }
        }
    }

    private static final class Metrics implements OutboxMetrics {
        private final AtomicInteger claimed = new AtomicInteger();
        private final AtomicInteger sent = new AtomicInteger();
        private final AtomicInteger failedRetryable = new AtomicInteger();
        private final AtomicInteger leaseLost = new AtomicInteger();
        private final AtomicInteger claimFailed = new AtomicInteger();
        private final List<Duration> oldestLags = new CopyOnWriteArrayList<>();

        @Override
        public void claimed(int count) {
            claimed.addAndGet(count);
        }

        @Override
        public void sent() {
            sent.incrementAndGet();
        }

        @Override
        public void failed(boolean retryable) {
            if (retryable) {
                failedRetryable.incrementAndGet();
            }
        }

        @Override
        public void leaseLost() {
            leaseLost.incrementAndGet();
        }

        @Override
        public void claimFailed() {
            claimFailed.incrementAndGet();
        }

        @Override
        public void oldestLag(Duration lag) {
            oldestLags.add(lag);
        }
    }
}
