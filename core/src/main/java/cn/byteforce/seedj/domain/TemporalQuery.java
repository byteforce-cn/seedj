package cn.byteforce.seedj.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 时点查询参数。
 *
 * <p>{@code asOf} 指定业务时点，{@code recordedAsOf} 指定系统记录时点；
 * 两者都为 {@code null} 表示查询当前版本。{@code minVersion} 是写后读一致性约束：
 * 返回版本低于该值时由存储层拒绝（{@code EIS-409-003}）。</p>
 *
 * @param asOf         业务时点，可为 {@code null}
 * @param recordedAsOf 系统时点，可为 {@code null}
 * @param minVersion   最小对象版本，可为 {@code null}
 * @author ByteForce
 * @since 1.0.0
 */
public record TemporalQuery(Instant asOf, Instant recordedAsOf, Long minVersion) {

    public TemporalQuery {
        if (minVersion != null && minVersion < 1) {
            throw new IllegalArgumentException("minVersion must be >= 1, got: " + minVersion);
        }
        asOf = micros(asOf);
        recordedAsOf = micros(recordedAsOf);
    }

    /**
     * 当前版本查询。
     *
     * @return 无时点、无版本约束的查询
     */
    public static TemporalQuery current() {
        return new TemporalQuery(null, null, null);
    }

    /**
     * 本查询对应的视图。
     *
     * @return {@link TemporalView#CURRENT} 或 {@link TemporalView#AS_OF}
     */
    public TemporalView view() {
        return TemporalView.of(this);
    }

    private static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
