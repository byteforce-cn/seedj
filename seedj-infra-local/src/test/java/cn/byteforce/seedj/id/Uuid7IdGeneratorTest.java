package cn.byteforce.seedj.id;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UUIDv7 生成器：版本/变体、唯一性、单调性、时钟编码。
 */
class Uuid7IdGeneratorTest {

    private final Clock clock = Clocks.fixed(Instant.parse("2026-10-08T09:00:00Z"));

    private final Uuid7IdGenerator generator = new Uuid7IdGenerator(clock);

    @Test
    void producesRfc9562Version7Variant2() {
        UUID id = generator.id();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
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
    void encodesClockMilliseconds() {
        UUID id = generator.id();
        long millis = id.getMostSignificantBits() >>> 16;
        assertThat(millis).isEqualTo(clock.now().toEpochMilli());
    }
}
