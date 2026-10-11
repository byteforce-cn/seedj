package cn.byteforce.seedj.starter.eis.temporal;

import cn.byteforce.seedj.starter.eis.core.EisFieldKind;
import cn.byteforce.seedj.starter.eis.core.EisObjectRepository;
import cn.byteforce.seedj.starter.eis.core.EisObjectRow;
import cn.byteforce.seedj.starter.eis.core.EisObjectType;
import cn.byteforce.seedj.starter.eis.core.EisObjectTypeRegistry;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisTemporalController} 契约响应测试（时点传递 / 半开窗口 / 脱敏 / 404）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisTemporalControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID ID = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private EisObjectRepository repository;

    private EisTemporalController controller;

    @BeforeEach
    void setUp() {
        repository = mock(EisObjectRepository.class);
        EisObjectType type = EisObjectType.builder("t_user")
                .field("name", EisFieldKind.TEXT)
                .field("secret_hash", EisFieldKind.TEXT)
                .sensitive("secret_hash")
                .build();
        controller = new EisTemporalController(repository, new EisObjectTypeRegistry(List.of(type)));
    }

    private static EisObjectRow row(long version, Instant validFrom, Instant validTo, Instant recordedAt,
                                    Instant supersededAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "alice");
        data.put("secret_hash", "$2a$hash");
        return new EisObjectRow("t_user", ID, version, validFrom, validTo, recordedAt, supersededAt, ACTOR,
                validFrom, null, null, data);
    }

    @Test
    void getObjectReturnsProjectionWithSensitiveFieldsRemoved() {
        when(repository.asOf(eq("t_user"), eq(ID), any(), any()))
                .thenReturn(Optional.of(row(2L, NOW, null, NOW, null)));

        ResponseEntity<Map<String, Object>> response = controller.getObject("t_user", ID.toString(), null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("object_type", "t_user").containsEntry("id", ID)
                .containsEntry("version", 2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsEntry("name", "alice").doesNotContainKey("secret_hash");
    }

    @Test
    void getObjectPassesParsedTimesToRepository() {
        when(repository.asOf(eq("t_user"), eq(ID), any(), any())).thenReturn(Optional.empty());
        Instant asOf = Instant.parse("2026-10-01T00:00:00Z");
        Instant recorded = Instant.parse("2026-10-02T00:00:00Z");

        assertThatThrownBy(() -> controller.getObject("t_user", ID.toString(), asOf.toString(),
                recorded.toString()))
                .isInstanceOf(EisRequestException.class);

        verify(repository).asOf("t_user", ID, asOf, recorded);
    }

    @Test
    void getObjectMissingVersionIsNotFound() {
        when(repository.asOf(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getObject("t_user", ID.toString(), null, null))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-404-001"));
    }

    @Test
    void getObjectRejectsBadInputs() {
        assertThatThrownBy(() -> controller.getObject("t_user", "not-a-uuid", null, null))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.getObject("t_user", ID.toString(), "not-a-time", null))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.getObject("unknown_type", ID.toString(), null, null))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-404-001"));
    }

    @Test
    void getVersionUsesValidAtAndRecordedAt() {
        when(repository.asOf(any(), any(), any(), any())).thenReturn(Optional.of(row(1L, NOW, null, NOW, null)));
        Instant valid = Instant.parse("2026-10-05T00:00:00Z");
        Instant recorded = Instant.parse("2026-10-06T00:00:00Z");

        controller.getVersion("t_user", ID.toString(), valid.toString(), recorded.toString());

        verify(repository).asOf("t_user", ID, valid, recorded);
    }

    @Test
    void historyAppliesTimelineWindow() {
        EisObjectRow first = row(1L, NOW, NOW.plusSeconds(60), NOW, NOW.plusSeconds(60));
        EisObjectRow second = row(2L, NOW.plusSeconds(60), null, NOW.plusSeconds(60), null);
        when(repository.history("t_user", ID)).thenReturn(List.of(first, second));

        // valid 轴窗口 [NOW, NOW+30s) 只命中第一版
        ResponseEntity<Map<String, Object>> response = controller.getHistory("t_user", ID.toString(), "valid",
                NOW.toString(), NOW.plusSeconds(30).toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) response.getBody().get("versions");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("version", 1L);
    }

    @Test
    void historyDefaultsToBothAndRejectsBadTimeline() {
        when(repository.history("t_user", ID)).thenReturn(List.of(row(1L, NOW, null, NOW, null)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) controller
                .getHistory("t_user", ID.toString(), "both", null, null).getBody().get("versions");
        assertThat(versions).hasSize(1);

        assertThatThrownBy(() -> controller.getHistory("t_user", ID.toString(), "weird", null, null))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.getHistory("t_user", ID.toString(), "valid", "bad", null))
                .isInstanceOf(EisRequestException.class);
    }

    @Test
    void historyRecordedAxisWindow() {
        EisObjectRow first = row(1L, NOW, NOW.plusSeconds(60), NOW, NOW.plusSeconds(60));
        EisObjectRow second = row(2L, NOW.plusSeconds(60), null, NOW.plusSeconds(60), null);
        when(repository.history("t_user", ID)).thenReturn(List.of(first, second));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) controller
                .getHistory("t_user", ID.toString(), "recorded", NOW.plusSeconds(90).toString(), null)
                .getBody().get("versions");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("version", 2L);
    }

    @Test
    void projectionRemovesSensitiveFieldsOnly() {
        Map<String, Object> projected = EisObjectProjection.toResponse(row(1L, NOW, null, NOW, null),
                EisObjectType.builder("t_user").field("name", EisFieldKind.TEXT)
                        .field("secret_hash", EisFieldKind.TEXT).sensitive("secret_hash").build());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) projected.get("data");
        assertThat(data).containsOnlyKeys("name");
        tools.jackson.databind.JsonNode serialized = MAPPER.valueToTree(projected);
        assertThat(serialized.get("object_type").stringValue()).isEqualTo("t_user");
    }
}
