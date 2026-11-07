package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 封口/追加语义：版本递增、系统时间单调、创建/更新字段、迟到事实。 */
class SealingTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    @Test
    void createProducesVersionOne() {
        AppendResult result =
                Sealing.next(
                        AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of("name", "v1")),
                        null,
                        T0);
        assertThat(result.appended().version().version()).isEqualTo(1);
        assertThat(result.appended().version().createdBy()).isEqualTo(ACTOR);
        assertThat(result.appended().version().createdAt()).isEqualTo(T0);
        assertThat(result.appended().version().updatedBy()).isNull();
        assertThat(result.appended().version().isCurrent()).isTrue();
        assertThat(result.superseded()).isEmpty();
    }

    @Test
    void correctionSealsPreviousAtNewRecordedAt() {
        TemporalRow first =
                Sealing.next(
                                AppendRequest.create(
                                        "probe", ID, T0, null, ACTOR, Map.of("name", "v1")),
                                null,
                                T0)
                        .appended();
        AppendResult second =
                Sealing.next(
                        AppendRequest.correction(
                                "probe", ID, T0, null, ACTOR, Map.of("name", "v2")),
                        first,
                        T0.plusSeconds(60));
        assertThat(second.appended().version().version()).isEqualTo(2);
        assertThat(second.superseded().orElseThrow().version().supersededAt())
                .isEqualTo(second.appended().version().recordedAt());
        assertThat(second.appended().version().createdAt()).isEqualTo(first.version().createdAt());
        assertThat(second.appended().version().updatedBy()).isEqualTo(ACTOR);
    }

    @Test
    void sameMicrosecondStillAdvancesRecordedAt() {
        TemporalRow first =
                Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of()), null, T0)
                        .appended();
        TemporalRow second =
                Sealing.next(
                                AppendRequest.correction("probe", ID, T0, null, ACTOR, Map.of()),
                                first,
                                T0)
                        .appended();
        assertThat(second.version().recordedAt()).isEqualTo(T0.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void lateFactAllowsHistoricalValidRangeButRejectsFuture() {
        TemporalRow first =
                Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of()), null, T0)
                        .appended();
        TemporalRow late =
                Sealing.next(
                                AppendRequest.lateFact(
                                        "probe", ID, T0.minusSeconds(3600), null, ACTOR, Map.of()),
                                first,
                                T0.plusSeconds(60))
                        .appended();
        assertThat(late.version().validFrom()).isEqualTo(T0.minusSeconds(3600));
        assertThatThrownBy(
                        () ->
                                Sealing.next(
                                        AppendRequest.lateFact(
                                                "probe",
                                                ID,
                                                T0.plusSeconds(120),
                                                null,
                                                ACTOR,
                                                Map.of()),
                                        first,
                                        T0.plusSeconds(60)))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION);
    }

    @Test
    void createOnExistingObjectIsConflict() {
        TemporalRow first =
                Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of()), null, T0)
                        .appended();
        assertThatThrownBy(
                        () ->
                                Sealing.next(
                                        AppendRequest.create(
                                                "probe", ID, T0, null, ACTOR, Map.of()),
                                        first,
                                        T0.plusSeconds(1)))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.VERSION_CONFLICT);
    }

    @Test
    void correctionOnMissingObjectIsNotFound() {
        assertThatThrownBy(
                        () ->
                                Sealing.next(
                                        AppendRequest.correction(
                                                "probe", ID, T0, null, ACTOR, Map.of()),
                                        null,
                                        T0))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }
}
