package cn.byteforce.seedj.starter.ai.approval.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.im.ImMessage;
import cn.byteforce.seedj.im.ImMessageKind;
import cn.byteforce.seedj.starter.ai.approval.ApprovalAction;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.im.ImCardActionContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批卡片动作：调用 ApprovalService 的同一条 act 路径。 */
class ApprovalCardActionHandlerTest {

    private static final UUID DECISION = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");

    @Test
    @DisplayName("approve：调用 act 并返回 approved")
    void approveCallsApprovalService() {
        ApprovalService approvals = mock(ApprovalService.class);
        when(approvals.act(DECISION, ApprovalAction.APPROVE, APPROVER, null))
                .thenReturn(
                        new ApprovalSnapshot(
                                "i-1",
                                "t-1",
                                ApprovalStatus.APPROVED,
                                Instant.parse("2026-11-19T00:00:00Z"),
                                APPROVER,
                                Instant.parse("2026-11-19T01:00:00Z"),
                                "sha256:a",
                                null));
        ApprovalCardActionHandler handler = new ApprovalCardActionHandler(approvals);

        Map<String, Object> result =
                handler.handle(
                        new ImCardActionContext(
                                message(),
                                Map.of(
                                        "schema",
                                        ApprovalCardNotifier.SCHEMA,
                                        "decision_id",
                                        DECISION.toString()),
                                Map.of("id", "approve"),
                                caller()));

        verify(approvals).act(DECISION, ApprovalAction.APPROVE, APPROVER, null);
        assertThat(result).containsEntry("status", "approved");
    }

    @Test
    @DisplayName("缺 decision_id 或未知动作：400")
    void invalidCardIsRejected() {
        ApprovalService approvals = mock(ApprovalService.class);
        ApprovalCardActionHandler handler = new ApprovalCardActionHandler(approvals);

        assertThatThrownBy(
                        () ->
                                handler.handle(
                                        new ImCardActionContext(
                                                message(),
                                                Map.of("schema", ApprovalCardNotifier.SCHEMA),
                                                Map.of("id", "approve"),
                                                caller())))
                .isInstanceOfSatisfying(
                        cn.byteforce.seedj.core.exception.ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(
                                                cn.byteforce.seedj.core.exception.ContractErrorCode
                                                        .INVALID_PAYLOAD));
        assertThatThrownBy(
                        () ->
                                handler.handle(
                                        new ImCardActionContext(
                                                message(),
                                                Map.of(
                                                        "schema",
                                                        ApprovalCardNotifier.SCHEMA,
                                                        "decision_id",
                                                        DECISION.toString()),
                                                Map.of("id", "maybe"),
                                                caller())))
                .isInstanceOfSatisfying(
                        cn.byteforce.seedj.core.exception.ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(
                                                cn.byteforce.seedj.core.exception.ContractErrorCode
                                                        .INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("审批人 id 不是 UUID：403")
    void nonUuidApproverIsForbidden() {
        ApprovalService approvals = mock(ApprovalService.class);
        ApprovalCardActionHandler handler = new ApprovalCardActionHandler(approvals);
        Caller caller =
                new Caller(
                        "alice",
                        "alice",
                        TENANT.toString(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        null,
                        null);

        assertThatThrownBy(
                        () ->
                                handler.handle(
                                        new ImCardActionContext(
                                                message(),
                                                Map.of(
                                                        "schema",
                                                        ApprovalCardNotifier.SCHEMA,
                                                        "decision_id",
                                                        DECISION.toString()),
                                                Map.of("id", "approve"),
                                                caller)))
                .isInstanceOfSatisfying(
                        cn.byteforce.seedj.core.exception.ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(
                                                cn.byteforce.seedj.core.exception.ContractErrorCode
                                                        .FORBIDDEN));
    }

    private static ImMessage message() {
        return new ImMessage(
                UUID.randomUUID(),
                TENANT.toString(),
                UUID.randomUUID(),
                1,
                "system",
                ImMessageKind.CARD,
                Map.of(
                        "schema", ApprovalCardNotifier.SCHEMA,
                        "decision_id", DECISION.toString(),
                        "actions", java.util.List.of(Map.of("id", "approve", "label", "批准"))),
                null,
                Instant.parse("2026-11-19T00:00:00Z"),
                null,
                null);
    }

    private static Caller caller() {
        return new Caller(
                APPROVER.toString(),
                "approver",
                TENANT.toString(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                null,
                null);
    }
}
