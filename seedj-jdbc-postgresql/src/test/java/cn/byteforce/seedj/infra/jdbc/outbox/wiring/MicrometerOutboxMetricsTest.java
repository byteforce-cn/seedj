package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerOutboxMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerOutboxMetrics metrics = new MicrometerOutboxMetrics(registry);

    @Test
    void claimedAccumulatesTheClaimedCount() {
        metrics.claimed(3);
        metrics.claimed(2);

        assertThat(registry.get("seedj.outbox.claimed").counter().count()).isEqualTo(5.0);
    }

    @Test
    void sentCountsSuccessfulDeliveries() {
        metrics.sent();
        metrics.sent();

        assertThat(registry.get("seedj.outbox.sent").counter().count()).isEqualTo(2.0);
    }

    @Test
    void failedIsTaggedByRetryability() {
        metrics.failed(true);
        metrics.failed(true);
        metrics.failed(false);

        assertThat(registry.get("seedj.outbox.failed").tag("retryable", "true").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("seedj.outbox.failed").tag("retryable", "false").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void leaseLostAndClaimFailedUseDedicatedCounters() {
        metrics.leaseLost();
        metrics.claimFailed();
        metrics.claimFailed();

        assertThat(registry.get("seedj.outbox.lease.lost").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("seedj.outbox.claim.failed").counter().count()).isEqualTo(2.0);
    }

    @Test
    void oldestLagIsRecordedOnTheLagTimer() {
        metrics.oldestLag(Duration.ofSeconds(2));
        metrics.oldestLag(Duration.ofMillis(500));

        assertThat(registry.get("seedj.outbox.lag").timer().count()).isEqualTo(2);
        assertThat(registry.get("seedj.outbox.lag").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(2_500.0);
    }
}
