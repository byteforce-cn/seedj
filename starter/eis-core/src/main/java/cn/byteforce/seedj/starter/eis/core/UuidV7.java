package cn.byteforce.seedj.starter.eis.core;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UUIDv7 生成器：时间有序、离线可生成（EIS 契约 {@code eis_id} 域要求）。
 *
 * <p>位布局（RFC 9562）：48 位毫秒时间戳 + 4 位版本（7）+ 12 位随机 A +
 * 2 位变体（{@code 10}）+ 62 位随机 B。同一毫秒内通过随机 A 单调递增
 * （溢出后回退为随机重排）保证生成序列有序，避免同毫秒撞车。</p>
 *
 * <p><b>不进 coord IdGen</b>：EIS 评估点 7 冻结——ID 一律应用侧生成，
 * coord IdGen 留在工具面。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class UuidV7 {

    /** 同一毫秒内的随机 A 上界（12 位）。 */
    private static final int MAX_RAND_A = 0xFFF;

    private static final Object LOCK = new Object();

    private static long lastMillis = -1L;

    private static int lastRandA = 0;

    private UuidV7() {
    }

    /**
     * 以时钟当前时刻生成 UUIDv7。
     *
     * @param clock EIS 时钟
     * @return 时间有序 UUID
     */
    public static UUID generate(EisClock clock) {
        return generate(clock.now());
    }

    /**
     * 以指定时刻生成 UUIDv7。
     *
     * @param instant 时间戳（毫秒精度参与排序）
     * @return 时间有序 UUID
     */
    public static UUID generate(Instant instant) {
        long millis = instant.toEpochMilli();
        int randA;
        synchronized (LOCK) {
            if (millis == lastMillis) {
                randA = (lastRandA + 1) & MAX_RAND_A;
                if (randA == 0) {
                    // 同毫秒 12 位随机耗尽：重新随机并继续（概率可忽略）
                    randA = ThreadLocalRandom.current().nextInt(1, MAX_RAND_A + 1);
                }
            } else {
                randA = ThreadLocalRandom.current().nextInt(MAX_RAND_A + 1);
            }
            lastMillis = millis;
            lastRandA = randA;
        }
        long randB = ThreadLocalRandom.current().nextLong() >>> 2; // 62 位
        long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | (0x7L << 12) | randA;
        long lsb = (0x2L << 62) | randB;
        return new UUID(msb, lsb);
    }
}
