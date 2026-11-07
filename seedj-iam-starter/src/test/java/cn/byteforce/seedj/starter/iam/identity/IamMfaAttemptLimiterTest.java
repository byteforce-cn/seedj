package cn.byteforce.seedj.starter.iam.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IamMfaAttemptLimiterTest {

    private final Instant start = Instant.parse("2026-01-01T00:00:00Z");
    private final AdjustableClock clock = new AdjustableClock(start);
    private final IamMfaAttemptLimiter limiter =
            new IamMfaAttemptLimiter(2, Duration.ofMinutes(5), clock);

    @Test
    void locksAfterMaxFailuresAndIgnoresOtherUsers() {
        limiter.failure("u-1");

        assertThat(limiter.locked("u-1")).isFalse();

        limiter.failure("u-1");

        assertThat(limiter.locked("u-1")).isTrue();
        assertThat(limiter.locked("u-2")).isFalse();
    }

    @Test
    void lockExpiresWithTheWindowAndNextFailureStartsAFreshWindow() {
        limiter.failure("u-1");
        limiter.failure("u-1");
        assertThat(limiter.locked("u-1")).isTrue();

        clock.advance(Duration.ofMinutes(6));

        assertThat(limiter.locked("u-1")).isFalse();

        // The failure after expiry must start a new window with a single failure.
        limiter.failure("u-1");
        assertThat(limiter.locked("u-1")).isFalse();
        limiter.failure("u-1");
        assertThat(limiter.locked("u-1")).isTrue();
    }

    @Test
    void successClearsTheFailureCounter() {
        limiter.failure("u-1");
        limiter.failure("u-1");
        assertThat(limiter.locked("u-1")).isTrue();

        limiter.success("u-1");

        assertThat(limiter.locked("u-1")).isFalse();
    }

    @Test
    void normalizesMaxFailuresAndWindow() {
        IamMfaAttemptLimiter lenient = new IamMfaAttemptLimiter(0, null, null);

        lenient.failure("u-1");

        assertThat(lenient.locked("u-1")).isTrue();
    }

    private static final class AdjustableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneId zone = ZoneOffset.UTC;

        private AdjustableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            throw new UnsupportedOperationException("fixed test zone");
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
