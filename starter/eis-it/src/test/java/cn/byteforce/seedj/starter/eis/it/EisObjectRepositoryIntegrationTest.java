package cn.byteforce.seedj.starter.eis.it;

import cn.byteforce.seedj.starter.eis.core.EisCommandRejectedException;
import cn.byteforce.seedj.starter.eis.core.EisFieldKind;
import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectRow;
import cn.byteforce.seedj.starter.eis.core.EisObjectType;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双时间轴对象存储真库集成测试（半开区间边界 / 封口相接 / 并发串行）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisObjectRepositoryIntegrationTest extends AbstractEisIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID REF = UUID.fromString("01900000-0000-7000-8000-0000000000bb");

    private static final Instant T0 = Instant.parse("2026-10-10T09:00:00Z");

    private EisObjectRepository repository;

    @BeforeEach
    void setUpRepository() {
        repository = new EisObjectRepository(jdbcTemplate(), new EisObjectTypeRegistry(List.of(widgetType())));
    }

    static EisObjectType widgetType() {
        return EisObjectType.builder("it_widget")
                .field("name", EisFieldKind.TEXT)
                .field("active", EisFieldKind.BOOLEAN)
                .field("ref", EisFieldKind.UUID)
                .field("meta", EisFieldKind.JSONB)
                .field("tags", EisFieldKind.TEXT_ARRAY)
                .build();
    }

    private static Map<String, Object> widgetData(String name) {
        return Map.of("name", name, "active", true, "ref", REF,
                "meta", MAPPER.readTree("{\"k\":1}"), "tags", List.of("a", "b"));
    }

    @Test
    void createAndReadBackRoundTripsAllKinds() {
        UUID id = UUID.randomUUID();
        repository.create("it_widget", id, widgetData("alice"), ACTOR, T0);

        EisObjectRow row = repository.findCurrent("it_widget", id).orElseThrow();

        assertThat(row.getVersion()).isEqualTo(1L);
        assertThat(row.getValidFrom()).isEqualTo(T0);
        assertThat(row.getRecordedAt()).isEqualTo(T0);
        assertThat(row.getSupersededAt()).isNull();
        assertThat(row.field("name")).isEqualTo("alice");
        assertThat(row.field("active")).isEqualTo(true);
        assertThat(row.field("ref")).isEqualTo(REF);
        assertThat(row.field("meta")).isInstanceOf(JsonNode.class);
        assertThat((JsonNode) row.field("meta")).isEqualTo(MAPPER.readTree("{\"k\":1}"));
        assertThat(row.field("tags")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void updateSealsCurrentAndAdjacentHistory() {
        UUID id = UUID.randomUUID();
        Instant t1 = T0.plusSeconds(60);
        repository.create("it_widget", id, widgetData("alice"), ACTOR, T0);

        EisObjectRow updated = transactionTemplate().execute(status ->
                repository.update("it_widget", id, Map.of("name", "bob"), ACTOR, t1));

        assertThat(updated.getVersion()).isEqualTo(2L);
        List<EisObjectRow> history = repository.history("it_widget", id);
        assertThat(history).hasSize(2);
        EisObjectRow first = history.get(0);
        EisObjectRow second = history.get(1);
        assertThat(first.getValidTo()).isEqualTo(t1);
        assertThat(first.getSupersededAt()).isEqualTo(t1);
        assertThat(second.getValidFrom()).isEqualTo(t1);
        assertThat(second.getRecordedAt()).isEqualTo(t1);
        assertThat(second.field("name")).isEqualTo("bob");
        // 未更新字段沿用
        assertThat(second.field("active")).isEqualTo(true);
    }

    @Test
    void bitemporalQueriesRespectHalfOpenIntervals() {
        UUID id = UUID.randomUUID();
        Instant t1 = T0.plusSeconds(60);
        repository.create("it_widget", id, widgetData("alice"), ACTOR, T0);
        transactionTemplate().executeWithoutResult(status ->
                repository.update("it_widget", id, Map.of("name", "bob"), ACTOR, t1));

        // 业务时点：t1 之前 → v1；t1 起（半开区间右端 = v1.valid_to，不命中）→ v2
        assertThat(repository.asOf("it_widget", id, t1.minusNanos(1_000), null).orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(repository.asOf("it_widget", id, t1, null).orElseThrow().getVersion()).isEqualTo(2L);
        // 系统时点：记录时刻含端点；替更时刻起 → v2
        assertThat(repository.asOf("it_widget", id, null, t1.minusNanos(1_000)).orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(repository.asOf("it_widget", id, null, t1).orElseThrow().getVersion()).isEqualTo(2L);
        // 双时间轴：替更前一刻的系统认知看 T0 业务时点 → v1；替更时刻起 → 旧版本已出认知窗（空）
        assertThat(repository.asOf("it_widget", id, T0, t1.minusNanos(1_000)).orElseThrow().getVersion())
                .isEqualTo(1L);
        assertThat(repository.asOf("it_widget", id, T0, t1)).isEmpty();
        // 业务时点早于首版 → 无命中
        assertThat(repository.asOf("it_widget", id, T0.minusNanos(1_000), null)).isEmpty();
    }

    @Test
    void concurrentUpdatesSerializeViaRowLock() throws Exception {
        UUID id = UUID.randomUUID();
        repository.create("it_widget", id, widgetData("alice"), ACTOR, T0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> transactionTemplate().execute(status ->
                    repository.update("it_widget", id, Map.of("name", "b1"), ACTOR, T0.plusSeconds(1))));
            Future<?> second = pool.submit(() -> transactionTemplate().execute(status ->
                    repository.update("it_widget", id, Map.of("name", "b2"), ACTOR, T0.plusSeconds(2))));
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<EisObjectRow> history = repository.history("it_widget", id);
        assertThat(history).hasSize(3);
        assertThat(history.get(2).getVersion()).isEqualTo(3L);
        assertThat(repository.findCurrent("it_widget", id).orElseThrow().getVersion()).isEqualTo(3L);
    }

    @Test
    void findCurrentAllBySupportsTypedCriteria() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        repository.create("it_widget", first, widgetData("alice"), ACTOR, T0);
        repository.create("it_widget", second, widgetData("bob"), ACTOR, T0.plusSeconds(1));

        assertThat(repository.findCurrentAllBy("it_widget", Map.of("name", "alice"))).hasSize(1);
        assertThat(repository.findCurrentAllBy("it_widget", Map.of("meta", MAPPER.readTree("{\"k\":1}"))))
                .hasSize(2);
        assertThat(repository.findCurrentAllBy("it_widget", Map.of("active", false))).isEmpty();
    }

    @Test
    void updateMissingRowIsRejected() {
        assertThatThrownBy(() -> transactionTemplate().executeWithoutResult(status ->
                repository.update("it_widget", UUID.randomUUID(), Map.of("name", "x"), ACTOR, T0)))
                .isInstanceOf(EisCommandRejectedException.class)
                .satisfies(ex -> assertThat(((EisCommandRejectedException) ex).getCode())
                        .isEqualTo("EIS-404-001"));
    }
}
