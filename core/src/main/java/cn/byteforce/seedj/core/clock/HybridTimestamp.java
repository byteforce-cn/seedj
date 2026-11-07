package cn.byteforce.seedj.core.clock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 混合逻辑时钟时间戳：(物理微秒, 逻辑计数器)。
 *
 * <p>物理部分为微秒精度 UTC，逻辑部分用于物理时间相同时的因果排序。 {@link #compareTo} 先比物理再比逻辑。
 */
public record HybridTimestamp(long physicalMicros, long logical)
        implements Comparable<HybridTimestamp> {

    public HybridTimestamp {
        if (logical < 0) {
            throw new IllegalArgumentException("logical must be >= 0");
        }
    }

    public static HybridTimestamp of(Instant instant, long logical) {
        return new HybridTimestamp(toMicros(instant), logical);
    }

    public static long toMicros(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1000L);
    }

    public Instant physicalInstant() {
        return Instant.EPOCH.plus(physicalMicros, ChronoUnit.MICROS);
    }

    @Override
    public int compareTo(HybridTimestamp o) {
        int c = Long.compare(physicalMicros, o.physicalMicros);
        return c != 0 ? c : Long.compare(logical, o.logical);
    }
}
