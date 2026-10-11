package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EisHashes} 与 {@link EisClock} 契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisHashesAndClockTest {

    @Test
    void sha256MatchesKnownVector() {
        assertThat(EisHashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256OfUtf8TextEqualsSha256OfBytes() {
        String text = "中文内容";

        assertThat(EisHashes.sha256Hex(text))
                .isEqualTo(EisHashes.sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void fixedClockAlwaysReturnsSameInstant() {
        Instant fixed = Instant.parse("2026-10-10T09:00:00Z");
        EisClock clock = EisClock.fixed(fixed);

        assertThat(clock.now()).isEqualTo(fixed);
        assertThat(clock.now()).isEqualTo(fixed);
    }

    @Test
    void monotonicClockAdvancesOnNonIncreasingSource() {
        EisClock monotonic = EisClock.monotonic(EisClock.fixed(Instant.parse("2026-10-10T09:00:00Z")));

        Instant first = monotonic.now();
        Instant second = monotonic.now();

        assertThat(second).isEqualTo(first.plusNanos(1_000L));
        assertThat(monotonic.now()).isEqualTo(first.plusNanos(2_000L));
    }

    @Test
    void monotonicClockPassesThroughIncreasingSource() {
        Instant base = Instant.parse("2026-10-10T09:00:00Z");
        EisClock source = new EisClock() {
            private int calls = 0;

            @Override
            public Instant now() {
                return base.plusSeconds(calls++);
            }
        };
        EisClock monotonic = EisClock.monotonic(source);

        Instant first = monotonic.now();
        Instant second = monotonic.now();

        assertThat(second).isEqualTo(first.plusSeconds(1));
    }
}
