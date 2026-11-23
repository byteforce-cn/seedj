package cn.byteforce.seedj.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalAction;
import cn.byteforce.seedj.starter.ai.approval.ApprovalProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.approval.LoggingApprovalNotifier;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import com.nimbusds.jose.JWSObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;

/** AI 审批端到端：ApprovalService 跑在 coord provider + 真 coord 集群上。 */
class WorkflowCoordIT {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF = UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final String ARGS_HASH = "sha256:9f2c";
    private static final String AUDIENCE = "ticket-service";
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private static CoordCluster cluster;
    private static ConfigurableApplicationContext context;
    private static WorkflowProvider workflows;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "coord workflow E2E requires Docker");
        cluster = CoordCluster.startWithAuth();
        context =
                new SpringApplicationBuilder(WorkflowItApplication.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                "spring.application.name=workflow-it",
                                "seedj.coord.enabled=true",
                                "seedj.coord.agent-host=127.0.0.1",
                                "seedj.coord.agent-port=" + cluster.agentPort(),
                                "seedj.coord.allow-insecure-plaintext=true",
                                "seedj.coord.auth.token=" + cluster.token(),
                                "seedj.workflow.provider=coord",
                                "seedj.workflow.coord.sign-admin=root")
                        .run();
        workflows = context.getBean(WorkflowProvider.class);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    @DisplayName("approve → grant 签发绑定 args_hash 的 token")
    void approveIssuesGrant() throws Exception {
        InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();
        ApprovalService service = service(ledger, "agent-approval", Duration.ofHours(4));
        service.registerDefinition();
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId));

        ApprovalSnapshot pending = service.start(intent(decisionId), AUDIENCE);
        assertThat(pending.status()).isEqualTo(ApprovalStatus.PENDING);

        ApprovalSnapshot approved = service.act(decisionId, ApprovalAction.APPROVE, APPROVER, "ok");
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(
                        workflows.history(
                                new cn.byteforce.seedj.workflow.spi.WorkflowInstanceId(
                                        approved.instanceId())))
                .extracting(cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent::eventType)
                .contains("INSTANCE_STARTED", "TASK_CREATED", "TASK_COMPLETED");

        String token = service.grant(decisionId);
        String payload =
                new String(JWSObject.parse(token).getPayload().toBytes(), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"status\":\"approved\"").contains(ARGS_HASH);
    }

    @Test
    @DisplayName("reject → APPROVAL(REJECTED)，不签发 token")
    void rejectDoesNotGrant() {
        InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();
        ApprovalService service = service(ledger, "agent-approval", Duration.ofHours(4));
        service.registerDefinition();
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId));
        service.start(intent(decisionId), AUDIENCE);

        ApprovalSnapshot rejected = service.act(decisionId, ApprovalAction.REJECT, APPROVER, "no");
        assertThat(rejected.status()).isEqualTo(ApprovalStatus.REJECTED);
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
    @DisplayName("deadline 到期 → APPROVAL(EXPIRED)，grant 拒绝")
    void deadlineExpiresApproval() {
        InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();
        ApprovalService service = service(ledger, "agent-approval-short", Duration.ofSeconds(5));
        service.registerDefinition();
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId));
        service.start(intent(decisionId), AUDIENCE);

        assertThat(service.expireOne(decisionId, NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.APPROVAL)
                .last()
                .satisfies(record -> assertThat(record.body().toString()).contains("expired"));
        assertThatThrownBy(() -> service.grant(decisionId)).isInstanceOf(ContractException.class);
    }

    private static ApprovalService service(
            InMemoryDecisionLedger ledger, String definitionName, Duration deadline) {
        return new ApprovalService(
                workflows,
                ledger,
                new DecisionTokenIssuer(ai().decision()),
                () -> NOW,
                new ApprovalProperties(
                        true,
                        "seedj-ai",
                        definitionName,
                        "1.0",
                        List.of(APPROVER.toString()),
                        deadline,
                        false,
                        null,
                        null),
                ai(),
                new LoggingApprovalNotifier());
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

    private static DecisionIntent intent(UUID decisionId) {
        return new DecisionIntent(
                decisionId,
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.IRREVERSIBLE_FINANCIAL,
                new CommandRef("ticket.refund", List.of(UUID.randomUUID()), null),
                ARGS_HASH,
                "a".repeat(64),
                Map.of(),
                "退款",
                List.<Evidence>of(),
                new ModelRef("m", "1", null, null),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF, null, "svc"),
                null);
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
        cn.byteforce.seedj.infra.coord.CoordAutoConfiguration.class,
        cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowAutoConfiguration.class
    })
    static class WorkflowItApplication {}
}
