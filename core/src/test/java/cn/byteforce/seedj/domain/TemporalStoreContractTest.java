package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.util.List;
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

    protected static final ScopePredicate ALL = ScopePredicate.all();

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
        return seedWith(ID, Map.of("name", name), validFrom);
    }

    protected TemporalRow seedWith(UUID id, Map<String, Object> fields, Instant validFrom) {
        return store().append(AppendRequest.create(TYPE, id, validFrom, null, ACTOR, fields))
                .appended();
    }

    protected TemporalRow correctWith(UUID id, Map<String, Object> fields, Instant validFrom,
                                      Instant now) {
        clock().set(now);
        return store().append(AppendRequest.correction(TYPE, id, validFrom, null, ACTOR, fields))
                .appended();
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

        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(59), null, null),
                ALL).orElseThrow().fields()).containsEntry("name", "v1");
        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(60), null, null),
                ALL).orElseThrow().fields()).containsEntry("name", "v2");
    }

    @Test
    void recordedAsOfReturnsBeliefAtSystemTime() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v2")));

        assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(30), null),
                ALL).orElseThrow().fields()).containsEntry("name", "v1");
        assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(90), null),
                ALL).orElseThrow().fields()).containsEntry("name", "v2");
    }

    @Test
    void minVersionAboveReadableIsRejected() {
        seed("v1", T0);
        assertThatThrownBy(() -> store().find(TYPE, ID, new TemporalQuery(null, null, 2L), ALL))
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
                new TemporalQuery(null, T0.plusSeconds(30), 2L), ALL))
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

        assertThat(store().history(TYPE, ID, ALL)).hasSize(2);
        assertThat(store().find(TYPE, ID, new TemporalQuery(T0.minusSeconds(1800), null, null),
                ALL).orElseThrow().fields()).containsEntry("name", "late");
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

        assertThat(store().history(TYPE, ID, ALL))
                .extracting(row -> row.version().version())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void correctionOnMissingObjectIsNotFound() {
        assertThat(store().find(TYPE, ID, TemporalQuery.current(), ALL)).isEmpty();
        assertThatThrownBy(() -> store().append(AppendRequest.correction(TYPE, ID, T0, null,
                ACTOR, Map.of())))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }

    @Test
    @DisplayName("search：等值过滤 + 分页，total 是过滤后的总数")
    void searchFiltersByEqualsAndPages() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000101"),
                Map.of("name", "a", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000102"),
                Map.of("name", "b", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000103"),
                Map.of("name", "c", "status", "inactive"), T0);

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE,
                Map.of("status", "active"), null, null, null, "name", SortDirection.ASC,
                new PageRequest(1, 1)), ALL);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).fields()).containsEntry("name", "a");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("search：按可过滤字段排序，两个方向都生效")
    void searchSortsByFilterableField() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000111"),
                Map.of("name", "b"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000112"),
                Map.of("name", "a"), T0);

        List<String> ascending = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, "name", SortDirection.ASC, new PageRequest(1, 20)), ALL)
                .items().stream().map(row -> (String) row.fields().get("name")).toList();
        List<String> descending = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, "name", SortDirection.DESC, new PageRequest(1, 20)), ALL)
                .items().stream().map(row -> (String) row.fields().get("name")).toList();

        assertThat(ascending).containsExactly("a", "b");
        assertThat(descending).containsExactly("b", "a");
    }

    @Test
    @DisplayName("search：as_of 返回历史行")
    void searchAsOfReturnsHistoricalRow() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000121"),
                Map.of("name", "v1"), T0);
        correctWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000121"),
                Map.of("name", "v2"), T0.plusSeconds(60), T0.plusSeconds(60));

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE, Map.of(),
                T0.plusSeconds(30), null, null, null, SortDirection.ASC,
                new PageRequest(1, 20)), ALL);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).fields()).containsEntry("name", "v1");
    }

    @Test
    @DisplayName("search：未知过滤字段/未知排序字段/非可过滤列都拒绝")
    void searchRejectsUnknownAndNonFilterableFields() {
        seed("a", T0);

        assertThatThrownBy(() -> store().search(new TemporalSearch(TYPE,
                Map.of("missing", "x"), null, null, null, null, SortDirection.ASC,
                new PageRequest(1, 20)), ALL))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, "missing", SortDirection.ASC, new PageRequest(1, 20)), ALL))
                .isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> store().search(new TemporalSearch(TYPE,
                Map.of("secret", "x"), null, null, null, null, SortDirection.ASC,
                new PageRequest(1, 20)), ALL))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("search：sort 必须是 sortable 字段，filterable 不够")
    void searchRejectsFilterableButNonSortableField() {
        seed("a", T0);

        assertThatThrownBy(() -> store().search(new TemporalSearch(TYPE, Map.of(),
                        null, null, null, "status", SortDirection.ASC,
                        new PageRequest(1, 20)), ALL))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("search：size>200 在分页构造期拒绝")
    void searchRejectsSizeOver200() {
        assertThatThrownBy(() -> new PageRequest(1, 201))
                .isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("search：字符串过滤值按字段类型转换")
    void searchCoercesStringFilters() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000131"),
                Map.of("name", "a", "hire_date", LocalDate.parse("2026-10-01")), T0);

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE,
                Map.of("hire_date", "2026-10-01"), null, null, null, null, SortDirection.ASC,
                new PageRequest(1, 20)), ALL);

        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void findReturnsEmptyWhenRowIsOutOfScope() {
        seedWith(ID, Map.of("name", "a", "status", "active"), T0);

        assertThat(store().find(TYPE, ID, TemporalQuery.current(),
                ScopePredicate.eq("status", "inactive"))).isEmpty();
        assertThat(store().find(TYPE, ID, TemporalQuery.current(),
                ScopePredicate.eq("status", "active"))).isPresent();
    }

    @Test
    @DisplayName("find：先选当前版本，再应用 scope；旧版本不能复活")
    void findUsesTheLatestVersionBeforeScope() {
        seedWith(ID, Map.of("name", "v1", "status", "active"), T0);
        correctWith(ID, Map.of("name", "v2", "status", "inactive"),
                T0.plusSeconds(60), T0.plusSeconds(60));

        assertThat(store().find(TYPE, ID, TemporalQuery.current(),
                ScopePredicate.eq("status", "active"))).isEmpty();
        assertThat(store().find(TYPE, ID, TemporalQuery.current(),
                ScopePredicate.eq("status", "inactive"))).isPresent();
    }

    @Test
    @DisplayName("find(as_of)：先选时点上的最新版本，再应用 scope")
    void findUsesTheLatestVersionBeforeScopeAtAnAsOfTime() {
        seedWith(ID, Map.of("name", "v1", "status", "active"), T0);
        correctWith(ID, Map.of("name", "v2", "status", "inactive"),
                T0.plusSeconds(60), T0.plusSeconds(60));

        assertThat(store().find(TYPE, ID,
                new TemporalQuery(T0.plusSeconds(90), null, null),
                ScopePredicate.eq("status", "active"))).isEmpty();
        assertThat(store().find(TYPE, ID,
                new TemporalQuery(T0.plusSeconds(90), null, null),
                ScopePredicate.eq("status", "inactive"))).isPresent();
    }

    @Test
    @DisplayName("search：先选当前版本，再应用 scope；旧版本不能复活")
    void searchUsesTheLatestVersionBeforeScope() {
        seedWith(ID, Map.of("name", "v1", "status", "active"), T0);
        correctWith(ID, Map.of("name", "v2", "status", "inactive"),
                T0.plusSeconds(60), T0.plusSeconds(60));

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, null, SortDirection.ASC, new PageRequest(1, 20)),
                ScopePredicate.eq("status", "active"));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    @DisplayName("search：等值过滤作用于当前版本，历史版本不会被匹配")
    void searchEqualsFiltersTheLatestVersion() {
        seedWith(ID, Map.of("name", "v1", "status", "active"), T0);
        correctWith(ID, Map.of("name", "v2", "status", "inactive"),
                T0.plusSeconds(60), T0.plusSeconds(60));

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE,
                        Map.of("status", "active"), null, null, null, null, SortDirection.ASC,
                        new PageRequest(1, 20)), ALL);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }

    @Test
    void searchAppliesScopeBeforePaging() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000201"),
                Map.of("name", "a", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000202"),
                Map.of("name", "b", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000203"),
                Map.of("name", "c", "status", "inactive"), T0);

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, "name", SortDirection.ASC, new PageRequest(1, 1)),
                ScopePredicate.eq("status", "active"));

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).fields()).containsEntry("name", "a");
    }

    @Test
    void searchTotalReflectsScope() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000211"),
                Map.of("name", "a", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000212"),
                Map.of("name", "b", "status", "active"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000213"),
                Map.of("name", "c", "status", "inactive"), T0);

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, null, SortDirection.ASC, new PageRequest(1, 20)),
                ScopePredicate.in("status", List.of("active")));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
    }

    @Test
    void historyAppliesScope() {
        seed("v1", T0);
        clock().advance(Duration.ofSeconds(60));
        store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR,
                Map.of("name", "v2")));

        assertThat(store().history(TYPE, ID, ALL)).hasSize(2);
        assertThat(store().history(TYPE, ID, ScopePredicate.eq("name", "v2")))
                .extracting(row -> row.version().version())
                .containsExactly(2L);
    }

    @Test
    void notNullScopeMatchesRowsWithValue() {
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000221"),
                Map.of("name", "a"), T0);
        seedWith(UUID.fromString("018f2e3a-1234-7000-8000-000000000222"),
                Map.of("name", "b", "status", "active"), T0);

        Page<TemporalRow> page = store().search(new TemporalSearch(TYPE, Map.of(), null, null,
                        null, null, SortDirection.ASC, new PageRequest(1, 20)),
                ScopePredicate.notNull("status"));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).fields()).containsEntry("name", "b");
    }
}
