package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdGenerator;

import cn.byteforce.seedj.core.clock.Clock;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUIDv7 生成器（RFC 9562）：48 位毫秒时间戳 + 版本 7 + 毫秒内计数器 + 62 位随机数。
 *
 * <p>同一毫秒内通过 12 位计数器保证严格递增；计数器溢出（4096 条/毫秒）时把时间戳
 * 推进 1 毫秒并清零计数器。时钟回拨时沿用上次时间戳并继续自增计数器，
 * 因此生成的 ID 始终单调递增。</p>
 *
 * <p>标识按字符串比较即按生成顺序比较，可直接用于对象、事件与审计条目的排序。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class Uuid7IdGenerator implements IdGenerator<UUID> {

    private static final long TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL;

    private static final long VERSION_BITS = 0x7000L;

    private static final long COUNTER_MASK = 0x0FFFL;

    private static final long VARIANT_BITS = 0x8000_0000_0000_0000L;

    private static final long RANDOM_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;

    private long lastMillis = -1L;

    private int counter;

    /**
     * 构造生成器。
     *
     * @param clock 取时入口（UTC、微秒精度）
     */
    public Uuid7IdGenerator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized UUID id() {
        long millis = clock.now().toEpochMilli();
        if (millis < 0L) {
            throw new IllegalArgumentException("clock before unix epoch: " + millis);
        }
        if (millis > lastMillis) {
            lastMillis = millis;
            counter = 0;
        } else {
            counter++;
            if (counter > COUNTER_MASK) {
                lastMillis++;
                counter = 0;
            }
        }
        long most = ((lastMillis & TIMESTAMP_MASK) << 16)
                | VERSION_BITS
                | (counter & COUNTER_MASK);
        long least = (ThreadLocalRandom.current().nextLong() & RANDOM_MASK) | VARIANT_BITS;
        return new UUID(most, least);
    }
}
