package cn.byteforce.seedj.starter.eis.core;

import cn.byteforce.seedj.starter.eis.core.web.EisCommandController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EisCommandController} 契约响应测试（状态码 / 响应体形状 / 参数校验）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCommandControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private EisCommandRuntime runtime;

    private EisCommandController controller;

    @BeforeEach
    void setUp() {
        runtime = mock(EisCommandRuntime.class);
        controller = new EisCommandController(runtime, () -> Optional.of(ACTOR));
    }

    private static JsonNode body(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    void submitAcceptedReturns202ContractBody() {
        UUID commandId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(runtime.submit(any())).thenReturn(
                EisCommandResult.accepted(commandId, List.of(eventId), new EisObjectRef("iam_user_account", SUBJECT)));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{\"username\":\"alice\"},"
                        + "\"idempotency_key\":\"k1\",\"targets\":[\"" + SUBJECT + "\"]}"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        Map<String, Object> result = response.getBody();
        assertThat(result).containsEntry("command_id", commandId).containsEntry("status", "accepted");
        assertThat(result.get("event_ids")).isEqualTo(List.of(eventId.toString()));
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) result.get("created");
        assertThat(created).containsEntry("type", "iam_user_account").containsEntry("id", SUBJECT);

        ArgumentCaptor<EisCommandRequest> requestCaptor = ArgumentCaptor.forClass(EisCommandRequest.class);
        org.mockito.Mockito.verify(runtime).submit(requestCaptor.capture());
        EisCommandRequest request = requestCaptor.getValue();
        assertThat(request.getCommandType()).isEqualTo("iam.user.create");
        assertThat(request.getIdempotencyKey()).isEqualTo("k1");
        assertThat(request.getActor()).isEqualTo(ACTOR);
        assertThat(request.getTargets()).containsExactly(SUBJECT);
    }

    @Test
    void submitRejectedReturnsCodeDerivedStatus() {
        when(runtime.submit(any())).thenReturn(
                EisCommandResult.rejected(UUID.randomUUID(), "IAM-403-002", "内置 root 账户不可禁用", null));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.disable", body(
                "{\"type\":\"iam.user.disable\",\"payload\":{},\"idempotency_key\":\"k2\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("status", "rejected")
                .containsEntry("code", "IAM-403-002")
                .containsEntry("reason", "内置 root 账户不可禁用");
    }

    @Test
    void submitRejectedWithReasonCodeIncludesIt() {
        when(runtime.submit(any())).thenReturn(
                EisCommandResult.rejected(UUID.randomUUID(), "IAM-403-003", "SoD 冲突", "SOD-001"));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.binding.grant", body(
                "{\"type\":\"iam.binding.grant\",\"payload\":{},\"idempotency_key\":\"k3\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("reason_code", "SOD-001");
    }

    @Test
    void submitConflictReturns409WithOriginalResult() {
        UUID originalId = UUID.randomUUID();
        when(runtime.submit(any())).thenReturn(EisCommandResult.conflict(originalId));
        when(runtime.findCommand(originalId)).thenReturn(Optional.of(new EisCommandStore.EisStoredCommand(
                originalId, "iam.user.create", NOW, ACTOR, "accepted", null, null, List.of(SUBJECT))));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{\"username\":\"bob\"},\"idempotency_key\":\"k4\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> result = response.getBody();
        assertThat(result).containsEntry("idempotency_key", "k4").containsEntry("original_command_id", originalId);
        assertThat(result.get("original_result")).isInstanceOf(Map.class);
    }

    @Test
    void submitReplayedAcceptedMarksReplay() {
        when(runtime.submit(any())).thenReturn(EisCommandResult.replay("accepted", UUID.randomUUID(),
                List.of(SUBJECT), null, null, null));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k5\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("replayed", true);
    }

    @Test
    void submitReplayedAcceptedWithCreatedIncludesCreated() {
        when(runtime.submit(any())).thenReturn(EisCommandResult.replay("accepted", UUID.randomUUID(),
                List.of(SUBJECT), null, null, null, new EisObjectRef("iam_user_account", SUBJECT)));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k5c\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("replayed", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) response.getBody().get("created");
        assertThat(created).containsEntry("type", "iam_user_account").containsEntry("id", SUBJECT);
    }

    @Test
    void submitConflictOriginalResultIncludesCreated() {
        UUID originalId = UUID.randomUUID();
        when(runtime.submit(any())).thenReturn(EisCommandResult.conflict(originalId));
        when(runtime.findCommand(originalId)).thenReturn(Optional.of(new EisCommandStore.EisStoredCommand(
                originalId, "iam.user.create", NOW, ACTOR, "accepted", null, null, List.of(SUBJECT),
                new EisObjectRef("iam_user_account", SUBJECT))));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{\"username\":\"bob\"},\"idempotency_key\":\"k4c\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> originalResult = (Map<String, Object>) response.getBody().get("original_result");
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) originalResult.get("created");
        assertThat(created).containsEntry("type", "iam_user_account").containsEntry("id", SUBJECT);
    }

    @Test
    void submitPathMismatchIsRejectedAsInvalid() {
        assertThatThrownBy(() -> controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.update\",\"payload\":{},\"idempotency_key\":\"k6\"}")))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-400-001"));
    }

    @Test
    void submitWithoutAuthenticatedActorIsUnauthenticated() {
        EisCommandController anonymous = new EisCommandController(runtime, Optional::empty);

        assertThatThrownBy(() -> anonymous.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k7\"}")))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-401-001"));
    }

    @Test
    void submitRejectsBadTargetsAndMissingFields() {
        assertThatThrownBy(() -> controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k8\",\"targets\":\"nope\"}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k9\",\"targets\":[\"not-a-uuid\"]}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{}}")))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> controller.submit("iam.user.create",
                MAPPER.createObjectNode()))
                .isInstanceOf(EisRequestException.class);
    }

    @Test
    void getReturnsStatusBody() {
        UUID commandId = UUID.randomUUID();
        when(runtime.findCommand(commandId)).thenReturn(Optional.of(new EisCommandStore.EisStoredCommand(
                commandId, "iam.user.create", NOW, ACTOR, "rejected", "用户名已存在: alice", "IAM-409-001",
                List.of())));

        ResponseEntity<Map<String, Object>> response = controller.get(commandId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("command_id", commandId)
                .containsEntry("status", "rejected")
                .containsEntry("code", "IAM-409-001")
                .containsEntry("reason", "用户名已存在: alice")
                .containsEntry("issued_at", NOW);
    }

    @Test
    void getIncludesCreatedRefWhenPresent() {
        UUID commandId = UUID.randomUUID();
        when(runtime.findCommand(commandId)).thenReturn(Optional.of(new EisCommandStore.EisStoredCommand(
                commandId, "iam.user.create", NOW, ACTOR, "accepted", null, null, List.of(SUBJECT),
                new EisObjectRef("iam_user_account", SUBJECT))));

        ResponseEntity<Map<String, Object>> response = controller.get(commandId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) response.getBody().get("created");
        assertThat(created).containsEntry("type", "iam_user_account").containsEntry("id", SUBJECT);
    }

    @Test
    void getMissingOrInvalidId() {
        UUID missing = UUID.randomUUID();
        when(runtime.findCommand(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(missing.toString()))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-404-001"));
        assertThatThrownBy(() -> controller.get("not-a-uuid"))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-400-001"));
    }

    @Test
    void rejectedStatusFallsBackTo400ForUnrecognizedCodes() {
        when(runtime.submit(any())).thenReturn(
                EisCommandResult.rejected(UUID.randomUUID(), "WEIRD", "奇形错误码", null));

        ResponseEntity<Map<String, Object>> response = controller.submit("iam.user.create", body(
                "{\"type\":\"iam.user.create\",\"payload\":{},\"idempotency_key\":\"k11\"}"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
