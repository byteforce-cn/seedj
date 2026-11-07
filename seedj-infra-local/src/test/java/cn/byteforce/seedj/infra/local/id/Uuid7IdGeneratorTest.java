package cn.byteforce.seedj.infra.local.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.TestClock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UUIDv7 生成器：版本/变体、唯一性、单调性、时钟编码。 */
class Uuid7IdGeneratorTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-10-08T09:00:00Z"));

    private final Uuid7IdGenerator generator = new Uuid7IdGenerator(clock);

    @Test
    void producesRfc9562Version7Variant2() {
        UUID id = generator.id();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void rejectsClockBeforeUnixEpoch() {
        Uuid7IdGenerator beforeEpoch =
                new Uuid7IdGenerator(new TestClock(Instant.parse("1969-12-31T23:59:59Z")));

        assertThatThrownBy(beforeEpoch::id)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before unix epoch");
    }

    @Test
    void isUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(generator.id())).isTrue();
        }
    }

    @Test
    void isMonotonicWithinSameMillisecond() {
        UUID first = generator.id();
        UUID second = generator.id();
        assertThat(second.toString()).isGreaterThan(first.toString());
    }

    @Test
    void counterOverflowStaysMonotonic() {
        UUID previous = generator.id();
        for (int i = 0; i < 5_000; i++) {
            UUID next = generator.id();
            assertThat(next.toString()).isGreaterThan(previous.toString());
            previous = next;
        }
    }

    @Test
    void clockRegressionStaysMonotonic() {
        UUID first = generator.id();
        clock.set(Instant.parse("2026-10-08T08:59:55Z"));
        assertThat(generator.id().toString()).isGreaterThan(first.toString());
    }

    @Test
    void encodesClockMilliseconds() {
        UUID id = generator.id();
        long millis = id.getMostSignificantBits() >>> 16;
        assertThat(millis).isEqualTo(clock.now().toEpochMilli());
    }
}
