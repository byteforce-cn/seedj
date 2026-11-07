package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 双时间轴对象版本：半开区间、当前判定、微秒截断、区间约束。 */
class ObjectVersionTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    @Test
    void validIntervalIsHalfOpen() {
        ObjectVersion v =
                new ObjectVersion(ID, 1, T0, T0.plusSeconds(60), T0, null, ACTOR, T0, null, null);
        assertThat(v.validAt(T0)).isTrue();
        assertThat(v.validAt(T0.plusSeconds(59))).isTrue();
        assertThat(v.validAt(T0.plusSeconds(60))).isFalse();
    }

    @Test
    void recordedIntervalIsHalfOpen() {
        ObjectVersion v =
                new ObjectVersion(ID, 1, T0, null, T0, T0.plusSeconds(60), ACTOR, T0, null, null);
        assertThat(v.recordedAt(T0.plusSeconds(59))).isTrue();
        assertThat(v.recordedAt(T0.plusSeconds(60))).isFalse();
    }

    @Test
    void currentRequiresBothAxesOpen() {
        assertThat(new ObjectVersion(ID, 1, T0, null, T0, null, ACTOR, T0, null, null).isCurrent())
                .isTrue();
        assertThat(
                        new ObjectVersion(
                                        ID,
                                        1,
                                        T0,
                                        T0.plusSeconds(1),
                                        T0,
                                        null,
                                        ACTOR,
                                        T0,
                                        null,
                                        null)
                                .isCurrent())
                .isFalse();
        assertThat(
                        new ObjectVersion(
                                        ID,
                                        1,
                                        T0,
                                        null,
                                        T0,
                                        T0.plusSeconds(1),
                                        ACTOR,
                                        T0,
                                        null,
                                        null)
                                .isCurrent())
                .isFalse();
    }

    @Test
    void truncatesToMicroseconds() {
        Instant nanos = Instant.parse("2026-10-08T09:00:00.123456789Z");
        ObjectVersion v =
                new ObjectVersion(ID, 1, nanos, null, nanos, null, ACTOR, nanos, null, null);
        assertThat(v.validFrom().getNano() % 1_000).isZero();
        assertThat(v.recordedAt().getNano() % 1_000).isZero();
        assertThat(v.createdAt().getNano() % 1_000).isZero();
    }

    @Test
    void rejectsInvertedOrEqualValidRange() {
        assertThatThrownBy(() -> new ObjectVersion(ID, 1, T0, T0, T0, null, ACTOR, T0, null, null))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION);
    }

    @Test
    void queryRejectsNonPositiveMinVersion() {
        assertThatThrownBy(() -> new TemporalQuery(null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemporalQuery.current().minVersion()).isNull();
        assertThat(TemporalQuery.current().asOf()).isNull();
    }
}
