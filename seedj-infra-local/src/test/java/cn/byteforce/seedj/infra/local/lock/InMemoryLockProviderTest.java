package cn.byteforce.seedj.infra.local.lock;

import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.lock.LockProviderContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存实现：契约测试 + 用可控时钟验证到期逻辑（不依赖 sleep）。
 */
class InMemoryLockProviderTest extends LockProviderContractTest {

    @Override
    protected LockProvider newProvider() {
        return new InMemoryLockProvider();
    }

    @Test
    @DisplayName("可控时钟：租约到期精确可判定")
    void expiryIsDeterministicWithFixedClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryLockProvider provider = new InMemoryLockProvider(clock);

        provider.tryAcquire("order:1", Duration.ofSeconds(30)).orElseThrow();

        clock.advance(Duration.ofSeconds(29));
        assertThat(provider.tryAcquire("order:1", Duration.ofSeconds(30))).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        assertThat(provider.tryAcquire("order:1", Duration.ofSeconds(30))).isPresent();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
