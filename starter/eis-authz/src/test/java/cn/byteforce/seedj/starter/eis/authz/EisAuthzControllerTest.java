package cn.byteforce.seedj.starter.eis.authz;

import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzDecision;
import cn.byteforce.seedj.starter.eis.authz.EisAuthzModels.EisAuthzResolution;
import cn.byteforce.seedj.starter.eis.core.EisRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisAuthzController} 契约响应与时间解析测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisAuthzControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final UUID PRINCIPAL = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private EisAuthzOperations operations;

    private EisAuthzController controller;

    @BeforeEach
    void setUp() {
        operations = mock(EisAuthzOperations.class);
        controller = new EisAuthzController(operations);
    }

    @Test
    void resolveReturnsContractBody() {
        Instant asOf = Instant.parse("2026-10-15T12:00:00Z");
        when(operations.resolve(eq("away"), eq(asOf), any(), any()))
                .thenReturn(new EisAuthzResolution(PRINCIPAL, asOf, List.of("approver"),
                        List.of("purchase.request.approve"), List.of("SOD-003")));

        ResponseEntity<Map<String, Object>> response = controller.resolve(MAPPER.readTree(
                "{\"principal\":\"away\",\"as_of\":\"2026-10-15T12:00:00Z\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("principal", PRINCIPAL)
                .containsEntry("as_of", "2026-10-15T12:00:00Z");
        assertThat(response.getBody().get("roles")).isEqualTo(List.of("approver"));
        assertThat(response.getBody().get("sod_warnings")).isEqualTo(List.of("SOD-003"));
    }

    @Test
    void resolvePassesScopeAndRecordedTime() {
        Instant asOf = Instant.parse("2026-10-15T12:00:00Z");
        Instant recorded = Instant.parse("2026-10-14T00:00:00Z");
        when(operations.resolve(any(), any(), any(), any()))
                .thenReturn(new EisAuthzResolution(PRINCIPAL, asOf, List.of(), List.of(), List.of()));

        controller.resolve(MAPPER.readTree("{\"principal\":\"away\","
                + "\"as_of\":\"2026-10-15T12:00:00Z\",\"recorded_as_of\":\"2026-10-14T00:00:00Z\","
                + "\"scope\":{\"department\":\"D001\"}}"));

        verify(operations).resolve("away", asOf, recorded, MAPPER.readTree("{\"department\":\"D001\"}"));
    }

    @Test
    void checkReturnsDecisionBody() {
        when(operations.check(any(), any(), any(), any()))
                .thenReturn(new EisAuthzDecision(false, "违反职责分离 SOD-001（采购申请与审批分离）", "SOD-001",
                        List.of()));

        Map<String, Object> body = controller.check(MAPPER.readTree(
                "{\"principal\":\"both\",\"permission\":\"purchase.request.approve\","
                        + "\"as_of\":\"2026-10-15T12:00:00Z\"}")).getBody();

        assertThat(body).containsEntry("allowed", false).containsEntry("reason_code", "SOD-001");
    }

    @Test
    void checkWithRequireAttributePassesConditionsAndReturnsState() {
        Instant asOf = Instant.parse("2026-10-15T12:00:00Z");
        when(operations.check(eq("away"), eq("purchase.request.approve"), eq(asOf), any(), any(), any()))
                .thenReturn(new EisAuthzDecision(true, "允许", "ALLOWED", List.of(),
                        EisAuthzAttributeState.FRESH));

        Map<String, Object> body = controller.check(MAPPER.readTree(
                "{\"principal\":\"away\",\"permission\":\"purchase.request.approve\","
                        + "\"as_of\":\"2026-10-15T12:00:00Z\","
                        + "\"attributes\":{\"attribute_state\":\"fresh\",\"snapshot\":{"
                        + "\"primary_position\":{\"position_id\":\"01900000-0000-7000-8000-0000000000b1\","
                        + "\"code\":\"P-1\",\"title\":\"Engineer\",\"grade\":\"P5\","
                        + "\"org_unit_id\":\"01900000-0000-7000-8000-0000000000c1\","
                        + "\"org_unit_code\":\"D001\",\"is_primary\":true}}},"
                        + "\"require_attribute\":[{\"key\":\"primary_position.grade\",\"values\":[\"P5\"]}]}"))
                .getBody();

        assertThat(body).containsEntry("attribute_state", "fresh");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EisAuthzAttributeCondition>> conditionsCaptor =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<EisAuthzAttributeSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(EisAuthzAttributeSnapshot.class);
        verify(operations).check(eq("away"), eq("purchase.request.approve"), eq(asOf), any(),
                conditionsCaptor.capture(), snapshotCaptor.capture());
        assertThat(conditionsCaptor.getValue()).hasSize(1);
        assertThat(conditionsCaptor.getValue().get(0).getKey()).isEqualTo("primary_position.grade");
        assertThat(snapshotCaptor.getValue().getState()).isEqualTo(EisAuthzAttributeState.FRESH);
        assertThat(snapshotCaptor.getValue().getAttributes().getPrimaryPosition().getGrade()).isEqualTo("P5");
    }

    @Test
    void checkWithoutAttributeFieldsUsesLegacyPathAndOmitsState() {
        Instant asOf = Instant.parse("2026-10-15T12:00:00Z");
        when(operations.check(any(), any(), any(), any()))
                .thenReturn(new EisAuthzDecision(true, "允许", "ALLOWED", List.of()));

        Map<String, Object> body = controller.check(MAPPER.readTree(
                "{\"principal\":\"away\",\"permission\":\"p\",\"as_of\":\"2026-10-15T12:00:00Z\"}"))
                .getBody();

        assertThat(body).doesNotContainKey("attribute_state");
        verify(operations).check("away", "p", asOf, null);
    }

    @Test
    void invalidRequireAttributeIsRejected() {
        assertThatThrownBy(() -> controller.check(MAPPER.readTree(
                "{\"principal\":\"away\",\"permission\":\"p\",\"as_of\":\"2026-10-15T12:00:00Z\","
                        + "\"require_attribute\":[{\"key\":\"no_such.attr\",\"values\":[\"x\"]}]}")))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-400-001"));
        assertThatThrownBy(() -> controller.check(MAPPER.readTree(
                "{\"principal\":\"away\",\"permission\":\"p\",\"as_of\":\"2026-10-15T12:00:00Z\","
                        + "\"require_attribute\":[{\"key\":\"employee_status\"}]}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.check(MAPPER.readTree(
                "{\"principal\":\"away\",\"permission\":\"p\",\"as_of\":\"2026-10-15T12:00:00Z\","
                        + "\"attributes\":{\"attribute_state\":\"bogus\"}}")))
                .isInstanceOf(EisRequestException.class);
    }

    @Test
    void resolveEchoesProvidedAttributeState() {
        Instant asOf = Instant.parse("2026-10-15T12:00:00Z");
        when(operations.resolve(any(), any(), any(), any()))
                .thenReturn(new EisAuthzResolution(PRINCIPAL, asOf, List.of(), List.of(), List.of()));

        Map<String, Object> withAttributes = controller.resolve(MAPPER.readTree(
                "{\"principal\":\"away\",\"as_of\":\"2026-10-15T12:00:00Z\","
                        + "\"attributes\":{\"attribute_state\":\"fresh\","
                        + "\"snapshot\":{\"employee_status\":\"active\"}}}")).getBody();
        Map<String, Object> withoutAttributes = controller.resolve(MAPPER.readTree(
                "{\"principal\":\"away\",\"as_of\":\"2026-10-15T12:00:00Z\"}")).getBody();

        assertThat(withAttributes).containsEntry("attribute_state", "fresh");
        assertThat(withoutAttributes).doesNotContainKey("attribute_state");
    }

    @Test
    void missingFieldsAreRejected() {
        assertThatThrownBy(() -> controller.check(MAPPER.readTree("{\"principal\":\"x\"}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.resolve(MAPPER.readTree("{\"principal\":\"x\"}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.resolve(MAPPER.readTree(
                "{\"principal\":\"x\",\"as_of\":\"not-a-time\"}")))
                .isInstanceOf(EisRequestException.class);
    }

    @Test
    void timeParsingAcceptsOffsetsAndLocalUtc() {
        Instant offset = ZonedDateTime.parse("2026-10-15T20:00:00+08:00").toInstant();
        assertThat(EisAuthzController.parseTime("2026-10-15T20:00:00+08:00")).isEqualTo(offset);
        assertThat(EisAuthzController.parseTime("2026-10-15T12:00:00"))
                .isEqualTo(Instant.parse("2026-10-15T12:00:00Z"));
        assertThat(EisAuthzController.parseTime(null)).isNull();
        assertThat(EisAuthzController.parseTime(" ")).isNull();
        assertThat(ZoneOffset.UTC).isNotNull();
    }
}
