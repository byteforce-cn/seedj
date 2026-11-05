package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TemporalStore} 契约测试套件。
 *
 * <p>实现方提供 {@link #store()}、{@link #clock()}（可推进的测试时钟）与
 * {@link #reset()}（清空状态）。所有用例必须通过：内存实现与 PostgreSQL 实现
 * 跑同一套断言，语义以规范参考内核为准。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class TemporalStoreContractTest {

    protected static final String TYPE = "probe";

    protected static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    protected static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    protected static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    /**
     * 被测存储。
     *
     * @return 存储实例
     */
    protected abstract TemporalStore store();

    /**
     * 被测存储使用的测试时钟。
     *
     * @return 测试时钟
     */
    protected abstract TestClock clock();

    /**
     * 清空存储，保证用例隔离。
     */
    protected abstract void reset();

    @BeforeEach
    void cleanState() {
        reset();
        clock().set(T0);
    }

    protected TemporalRow seed(String name, Instant validFrom) {
        return store().append(AppendRequest.create(TYPE, ID, validFrom, null, ACTOR,
                Map.of("name", name))).appended();
    }

    @Test
    void createIsVersionOneAndCurrent() {
        TemporalRow row = seed("v1", T0);
        assertThat(row.version().version()).isEqualTo(1L);
        assertThat(row.version().isCurrent()).isTrue();
        assertThat(row.version().createdBy()).isEqualTo(ACTOR);
        assertThat(row.fields()).containsEntry("name", "v1");
        assertThat(store().current(TYPE, ID)).contains(row);
    }

    @Test
    void appendSealsPreviousAndIncrementsVersion() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        AppendResult result = store().append(AppendRequest.correction(TYPE, ID, T0, null,
                ACTOR, Map.of("name", "v2")));
        assertThat(result.appended().version().version()).isEqualTo(2L);
        assertThat(result.superseded().orElseThrow().version().supersededAt())
                .isEqualTo(result.appended().version().recordedAt());
    }

    @Test
    void sameMicrosecondAppendDoesNotCollide() {
        seed("v1", T0);
        TemporalRow second = store().append(AppendRequest.correction(TYPE, ID, T0, null,
                ACTOR, Map.of("name", "v2"))).appended();
        assertThat(second.version().recordedAt()).isEqualTo(T0.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void asOfUsesHalfOpenBounds() {
        store().append(AppendRequest.create(TYPE, ID, T0, T0.plusSeconds(60), ACTOR,
                Map.of("name", "v1")));
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.correction(TYPE, ID, T0.plusSeconds(60), null, ACTOR,
                Map.of("name", "v2")));

        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(59), null, null))
                .orElseThrow().fields()).containsEntry("name", "v1");
        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(60), null, null))
                .orElseThrow().fields()).containsEntry("name", "v2");
    }

    @Test
    void recordedAsOfReturnsBeliefAtSystemTime() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v2")));

        assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(30), null))
                .orElseThrow().fields()).containsEntry("name", "v1");
        assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(90), null))
                .orElseThrow().fields()).containsEntry("name", "v2");
    }

    @Test
    void minVersionAboveReadableIsRejected() {
        seed("v1", T0);
        assertThatThrownBy(() -> store().find(TYPE, ID, new TemporalQuery(null, null, 2L)))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.READ_MODEL_NOT_READY);
    }

    @Test
    void minVersionWithHistoricalQueryAlsoComparesReturnedVersion() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v2")));

        assertThatThrownBy(() -> store().find(TYPE, ID,
                new TemporalQuery(null, T0.plusSeconds(30), 2L)))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.READ_MODEL_NOT_READY);
    }

    @Test
    void lateFactKeepsHistoryAndAllowsOverlap() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.lateFact(TYPE, ID, T0.minusSeconds(3600), null, ACTOR,
                Map.of("name", "late")));

        assertThat(store().history(TYPE, ID)).hasSize(2);
        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.minusSeconds(1800), null, null))
                .orElseThrow().fields()).containsEntry("name", "late");
    }

    @Test
    void historyIsOrderedByRecordedAt() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(10));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v2")));
        clock().advance(Duration.ofSeconds(10));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v3")));

        assertThat(store().history(TYPE, ID))
                .extracting(row -> row.version().version())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void correctionOnMissingObjectIsNotFound() {
        assertThat(store().find(TYPE, ID, TemporalQuery.current())).isEmpty();
        assertThatThrownBy(() -> store().append(AppendRequest.correction(TYPE, ID, T0, null,
                ACTOR, Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }
}
