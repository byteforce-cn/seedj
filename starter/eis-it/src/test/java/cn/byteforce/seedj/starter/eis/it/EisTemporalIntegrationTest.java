package cn.byteforce.seedj.starter.eis.it;

import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectType;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import cn.byteforce.seedj.starter.eis.temporal.EisTemporalController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时点查询端点真库集成测试（控制器 × 仓库 × JSONB/数组/敏感列投影）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisTemporalIntegrationTest extends AbstractEisIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final Instant T0 = Instant.parse("2026-10-10T09:00:00Z");

    private EisObjectRepository repository;

    private EisTemporalController controller;

    @BeforeEach
    void setUpTemporal() {
        EisObjectType type = EisObjectType.builder("it_widget")
                .field("name", cn.byteforce.seedj.starter.eis.core.EisFieldKind.TEXT)
                .field("active", cn.byteforce.seedj.starter.eis.core.EisFieldKind.BOOLEAN)
                .field("ref", cn.byteforce.seedj.starter.eis.core.EisFieldKind.UUID)
                .field("meta", cn.byteforce.seedj.starter.eis.core.EisFieldKind.JSONB)
                .field("tags", cn.byteforce.seedj.starter.eis.core.EisFieldKind.TEXT_ARRAY)
                .sensitive("meta")
                .build();
        repository = new EisObjectRepository(jdbcTemplate(), new EisObjectTypeRegistry(List.of(type)));
        controller = new EisTemporalController(repository, new EisObjectTypeRegistry(List.of(type)));
    }

    private UUID seedWidget(String name, Instant at) {
        UUID id = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("active", true);
        data.put("ref", null);
        data.put("meta", MAPPER.readTree("{\"note\":\"secret-ish\"}"));
        data.put("tags", List.of("t1"));
        repository.create("it_widget", id, data, ACTOR, at);
        return id;
    }

    @Test
    void currentQueryReturnsProjectionWithoutSensitiveField() {
        UUID id = seedWidget("alice", T0);

        ResponseEntity<Map<String, Object>> response = controller.getObject("it_widget", id.toString(), null,
                null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsEntry("name", "alice").doesNotContainKey("meta");
        assertThat(data.get("tags")).isEqualTo(List.of("t1"));
    }

    @Test
    void historicalQueriesHitCorrectVersions() {
        UUID id = seedWidget("alice", T0);
        Instant t1 = T0.plusSeconds(60);
        transactionTemplate().executeWithoutResult(status ->
                repository.update("it_widget", id, Map.of("name", "bob"), ACTOR, t1));

        // objects 端点：as_of = t1 → v2；as_of = t1-1µs → v1
        assertThat(controller.getObject("it_widget", id.toString(), t1.toString(), null).getBody())
                .containsEntry("version", 2L);
        assertThat(controller.getObject("it_widget", id.toString(), t1.minusNanos(1_000).toString(), null)
                .getBody()).containsEntry("version", 1L);

        // time-travel：recorded_at = t1 之前 → v1
        assertThat(controller.getVersion("it_widget", id.toString(), null, t1.minusNanos(1_000).toString())
                .getBody()).containsEntry("version", 1L);

        // history：两版相接
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) controller
                .getHistory("it_widget", id.toString(), "both", null, null).getBody().get("versions");
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).containsEntry("valid_to", t1);
        assertThat(versions.get(1)).containsEntry("valid_from", t1);
    }

    @Test
    void missingVersionIsNotFound() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> controller.getObject("it_widget", id.toString(), null, null))
                .isInstanceOf(cn.byteforce.seedj.starter.eis.core.EisRequestException.class);
    }
}
