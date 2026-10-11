package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UuidV7} 位布局、时间有序性与同毫秒单调性测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class UuidV7Test {

    @Test
    void generatesVersion7Variant2() {
        UUID uuid = UuidV7.generate(Instant.parse("2026-10-10T09:00:00Z"));

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    @Test
    void ordersByTimestamp() {
        UUID earlier = UuidV7.generate(Instant.parse("2026-10-10T09:00:00Z"));
        UUID later = UuidV7.generate(Instant.parse("2026-10-10T09:00:01Z"));

        assertThat(earlier.compareTo(later)).isNegative();
    }

    @Test
    void monotonicWithinSameMillisecond() {
        Instant instant = Instant.parse("2026-10-10T09:00:00.123Z");

        UUID first = UuidV7.generate(instant);
        UUID second = UuidV7.generate(instant);
        UUID third = UuidV7.generate(instant);

        assertThat(first.compareTo(second)).isNegative();
        assertThat(second.compareTo(third)).isNegative();
    }

    @Test
    void uniqueAcrossManyCalls() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(UuidV7.generate(Instant.parse("2026-10-10T09:00:00Z")));
        }

        assertThat(seen).hasSize(1000);
    }
}
