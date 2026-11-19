package cn.byteforce.seedj.starter.ai.plane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.infra.local.workflow.InMemoryWorkflowProvider;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.approval.LoggingApprovalNotifier;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 审批与 grant 端点：授权、状态机与 token 签发。 */
class ApprovalControllerTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final UUID CONTRACT = UUID.fromString("018f2e3a-0000-7000-8000-00000000c017");
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-0000-7000-8000-0000000e0001");
    private static final UUID TARGET = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final String COMMAND = "ticket.refund";
    private static final String PERMISSION = "agent:decide";
    private static final String AUDIENCE = "ticket-service";
    private static final Instant NOW = Instant.parse("2026-11-17T09:41:00Z");

    private final InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();

    private DecisionController controller;

    @BeforeEach
    void setUp() {
        AiProperties ai = properties();
        ConfiguredToolRegistry registry =
                new ConfiguredToolRegistry(
                        List.of(new AgentToolSpec(COMMAND, ActionClass.IRREVERSIBLE_FINANCIAL)));
        ApprovalProperties approvalProperties =
                new ApprovalProperties(
                        true,
                        "seedj-ai",
                        "agent-approval",
                        "1.0",
                        List.of(APPROVER.toString()),
                        Duration.ofHours(4),
                        false,
                        null,
                        null);
        InMemoryWorkflowProvider workflows =
                new InMemoryWorkflowProvider(
                        java.time.Clock.fixed(NOW, ZoneOffset.UTC),
                        () -> UUID.randomUUID().toString());
        ApprovalService approval =
                new ApprovalService(
                        workflows,
                        ledger,
                        new DecisionTokenIssuer(ai.decision()),
                        () -> NOW,
                        approvalProperties,
                        ai,
                        new LoggingApprovalNotifier());
        approval.registerDefinition();
        DecisionService decisions =
                new DecisionService(
                        new CapabilityRegistry(
                                List.of(
                                        new AiProperties.Contract(
                                                CONTRACT.toString(), List.of(COMMAND))),
                                registry),
                        registry,
                        ledger,
                        claims -> "jws-" + claims.decisionId(),
                        UUID::randomUUID,
                        () -> NOW,
                        ai,
                        cn.byteforce.seedj.starter.ai.policy.PolicyEvaluator.none(),
                        approval);
        CallerProvider callers =
                () ->
                        Optional.of(
                                new Caller(
                                        APPROVER.toString(),
                                        "approver",
                                        TENANT.toString(),
                                        Set.of("approver"),
                                        Set.of(PERMISSION),
                                        Set.of(),
                                        Set.of(),
                                        null,
                                        null));
        controller = new DecisionController(decisions, null, callers, PERMISSION, approval);
    }

    @Test
    @DisplayName("不可逆决策返回 pending；批准后才能 grant")
    void approveThenGrant() {
        DecisionService.DecisionResult pending = controller.decide(executeTool());
        assertThat(pending.verdict()).isEqualTo("pending_approval");
        assertThat(pending.token()).isNull();

        String before = controller.approval(pending.decisionId()).status();
        assertThat(before).isEqualTo("pending");

        assertThatThrownBy(() -> controller.grant(pending.decisionId()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.VERSION_CONFLICT));

        var approved =
                controller.act(
                        pending.decisionId(),
                        new DecisionController.ApprovalActionRequest("approve", "ok"));
        assertThat(approved.status()).isEqualTo("approved");
        assertThat(controller.grant(pending.decisionId()).token()).isNotBlank();
    }

    @Test
    @DisplayName("驳回后 grant 409")
    void rejectThenGrantConflicts() {
        DecisionService.DecisionResult pending = controller.decide(executeTool());
        controller.act(
                pending.decisionId(), new DecisionController.ApprovalActionRequest("reject", "no"));

        assertThatThrownBy(() -> controller.grant(pending.decisionId()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("未装配审批：approval/grant 返回 unavailable")
    void missingApprovalIsUnavailable() {
        DecisionController withoutApproval =
                new DecisionController(
                        new DecisionService(
                                new CapabilityRegistry(
                                        List.of(), new ConfiguredToolRegistry(List.of())),
                                new ConfiguredToolRegistry(List.of()),
                                ledger,
                                claims -> "jws",
                                UUID::randomUUID,
                                () -> NOW,
                                properties()),
                        null,
                        () ->
                                Optional.of(
                                        new Caller(
                                                APPROVER.toString(),
                                                "approver",
                                                TENANT.toString(),
                                                Set.of("approver"),
                                                Set.of(PERMISSION),
                                                Set.of(),
                                                Set.of(),
                                                null,
                                                null)),
                        PERMISSION);

        assertThatThrownBy(() -> withoutApproval.approval(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.UNAVAILABLE));
    }

    @Test
    @DisplayName("未知 action 是 400")
    void unknownActionIsInvalidPayload() {
        DecisionService.DecisionResult pending = controller.decide(executeTool());

        assertThatThrownBy(
                        () ->
                                controller.act(
                                        pending.decisionId(),
                                        new DecisionController.ApprovalActionRequest(
                                                "maybe", null)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    private static DecisionService.DecisionRequest executeTool() {
        return new DecisionService.DecisionRequest(
                CONTRACT,
                CONVERSATION,
                1,
                "execute_tool",
                new DecisionService.Command(
                        COMMAND, Map.of("amount", "128.00"), List.of(TARGET), null),
                "退款",
                List.of(new Evidence("message", "im://msg/1", "sha256:abc")),
                new DecisionService.Model("gpt", "2026-11", null, null),
                AUDIENCE);
    }

    private static AiProperties properties() {
        return new AiProperties(
                true,
                new AiProperties.Plane(
                        true,
                        TENANT,
                        PERMISSION,
                        new AiProperties.Reconcile(null, false),
                        Duration.ofMinutes(10)),
                null,
                null,
                new AiProperties.Identity(ACTOR.toString(), ON_BEHALF_OF.toString()),
                new AiProperties.Decision(
                        null,
                        AiProperties.DEFAULT_KID,
                        AiProperties.DEFAULT_DECISION_TTL,
                        AUDIENCE),
                List.of(),
                List.of());
    }
}
