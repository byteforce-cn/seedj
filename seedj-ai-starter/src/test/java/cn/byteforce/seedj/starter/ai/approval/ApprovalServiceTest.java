package cn.byteforce.seedj.starter.ai.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.infra.local.workflow.InMemoryWorkflowProvider;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import com.nimbusds.jose.JWSObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批状态机：start / act / grant / expire 的账本事实。 */
class ApprovalServiceTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF = UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final UUID OTHER = UUID.fromString("018f2e3a-0000-7000-8000-000000000120");
    private static final String ARGS_HASH = "sha256:9f2c";
    private static final String AUDIENCE = "ticket-service";
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private InMemoryDecisionLedger ledger;

    private InMemoryWorkflowProvider workflows;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryDecisionLedger();
        workflows =
                new InMemoryWorkflowProvider(
                        java.time.Clock.fixed(NOW, ZoneOffset.UTC),
                        () -> UUID.randomUUID().toString());
        service = service(List.of(APPROVER));
        service.registerDefinition();
    }

    @Test
    @DisplayName("start 绑定 args_hash 并写 PENDING")
    void startBindsArgsHashAndWritesPending() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));

        ApprovalSnapshot snapshot = service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        assertThat(snapshot.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(snapshot.argsHash()).isEqualTo(ARGS_HASH);
        assertThat(snapshot.instanceId()).isNotBlank();
        assertThat(snapshot.taskId()).isNotBlank();
        assertThat(ledger.byDecision(TENANT, decisionId))
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.APPROVAL);
    }

    @Test
    @DisplayName("批准后 grant 签出的 token 绑定 args_hash")
    void approveThenGrantSignsTokenBoundToArgsHash() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        ApprovalSnapshot approved = service.act(decisionId, ApprovalAction.APPROVE, APPROVER, "ok");
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);

        String token = service.grant(decisionId);
        String payload = payloadOf(token);
        assertThat(payload).contains("\"status\":\"approved\"");
        assertThat(payload).contains("\"args_hash\":\"" + ARGS_HASH + "\"");
        assertThat(payload).contains("\"approver\":\"" + APPROVER + "\"");
    }

    @Test
    @DisplayName("驳回写 REJECTED + outcome，grant 拒绝")
    void rejectWritesRejectedOutcomeAndGrantFails() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        service.act(decisionId, ApprovalAction.REJECT, APPROVER, "no");

        assertThatThrownBy(() -> service.grant(decisionId))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.VERSION_CONFLICT));
        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.OUTCOME)
                .singleElement()
                .satisfies(
                        record ->
                                assertThat(record.body().get("effect").toString())
                                        .contains("rejected"));
    }

    @Test
    @DisplayName("非审批人 403；自审批 403")
    void nonApproverAndSelfApproveAreForbidden() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        assertThatThrownBy(() -> service.act(decisionId, ApprovalAction.APPROVE, OTHER, "no"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));

        ApprovalService selfApprover = service(List.of(ON_BEHALF));
        selfApprover.registerDefinition();
        UUID selfDecision = UUID.randomUUID();
        ledger.appendIntent(intent(selfDecision, ARGS_HASH));
        selfApprover.start(intent(selfDecision, ARGS_HASH), AUDIENCE);

        assertThatThrownBy(
                        () ->
                                selfApprover.act(
                                        selfDecision, ApprovalAction.APPROVE, ON_BEHALF, "me"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("同一审批人重复批准是幂等的")
    void repeatedApproveIsIdempotent() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        service.act(decisionId, ApprovalAction.APPROVE, APPROVER, "ok");
        service.act(decisionId, ApprovalAction.APPROVE, APPROVER, "ok again");

        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.APPROVAL)
                .hasSize(2); // PENDING + APPROVED，不追加第二条 APPROVED
    }

    @Test
    @DisplayName("过期只追加一次 EXPIRED + outcome")
    void expireOneAppendsExpiredAndOutcomeOnce() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        Instant afterDeadline = NOW.plus(Duration.ofHours(5));
        assertThat(service.expireOne(decisionId, afterDeadline)).isEqualTo(1);
        assertThat(service.expireOne(decisionId, afterDeadline)).isZero();
        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.OUTCOME)
                .singleElement();
    }

    @Test
    @DisplayName("视图带 deadline 与审批人")
    void viewReturnsDeadlineAndApprovers() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        ApprovalView view = service.view(decisionId);

        assertThat(view.status()).isEqualTo("pending");
        assertThat(view.deadline()).isEqualTo(NOW.plus(Duration.ofHours(4)));
        assertThat(view.approvers()).containsExactly(APPROVER);
    }

    @Test
    @DisplayName("工作流启动失败：intent + APPROVAL(UNKNOWN) + outcome，抛 unavailable")
    void startFailureWritesUnknownAndOutcome() {
        cn.byteforce.seedj.workflow.spi.WorkflowProvider failing =
                org.mockito.Mockito.mock(cn.byteforce.seedj.workflow.spi.WorkflowProvider.class);
        org.mockito.Mockito.when(failing.start(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new cn.byteforce.seedj.workflow.spi.WorkflowProviderException("down"));
        ApprovalService failingService =
                new ApprovalService(
                        failing,
                        ledger,
                        new DecisionTokenIssuer(ai().decision()),
                        () -> NOW,
                        approvalProperties(List.of(APPROVER)),
                        ai(),
                        new LoggingApprovalNotifier());
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));

        assertThatThrownBy(() -> failingService.start(intent(decisionId, ARGS_HASH), AUDIENCE))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE));
        assertThat(ledger.byDecision(TENANT, decisionId))
                .extracting(DecisionRecord::kind)
                .containsExactly(
                        DecisionRecordKind.INTENT,
                        DecisionRecordKind.APPROVAL,
                        DecisionRecordKind.OUTCOME);
    }

    @Test
    @DisplayName("过期扫描在接上 pendingApprovals 前是 no-op")
    void expirePendingIsNoOpForNow() {
        assertThat(service.expirePending(NOW.plus(Duration.ofHours(5)))).isZero();
    }

    @Test
    @DisplayName("未批准的 grant 409")
    void grantBeforeApprovalIsConflict() {
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId, ARGS_HASH));
        service.start(intent(decisionId, ARGS_HASH), AUDIENCE);

        assertThatThrownBy(() -> service.grant(decisionId))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.VERSION_CONFLICT));
    }

    private ApprovalService service(List<UUID> approvers) {
        return new ApprovalService(
                workflows,
                ledger,
                new DecisionTokenIssuer(ai().decision()),
                () -> NOW,
                approvalProperties(approvers),
                ai(),
                new LoggingApprovalNotifier());
    }

    private static ApprovalProperties approvalProperties(List<UUID> approvers) {
        return new ApprovalProperties(
                true,
                "seedj-ai",
                "agent-approval",
                "1.0",
                approvers.stream().map(UUID::toString).toList(),
                Duration.ofHours(4),
                false,
                null,
                null);
    }

    private static AiProperties ai() {
        return new AiProperties(
                true,
                new AiProperties.Plane(true, TENANT, "agent:decide", null, Duration.ofMinutes(10)),
                new AiProperties.Guard(false),
                null,
                new AiProperties.Identity(ACTOR.toString(), ON_BEHALF.toString()),
                new AiProperties.Decision(null, "kid", Duration.ofSeconds(300), AUDIENCE),
                List.of(),
                List.of());
    }

    private static DecisionIntent intent(UUID decisionId, String argsHash) {
        return new DecisionIntent(
                decisionId,
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.IRREVERSIBLE_FINANCIAL,
                new CommandRef("ticket.refund", List.of(UUID.randomUUID()), null),
                argsHash,
                "a".repeat(64),
                Map.of(),
                "退款",
                List.<Evidence>of(),
                new ModelRef("m", "1", null, null),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF, null, "svc"),
                null);
    }

    private static String payloadOf(String token) {
        try {
            JWSObject jws = JWSObject.parse(token);
            return new String(jws.getPayload().toBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
