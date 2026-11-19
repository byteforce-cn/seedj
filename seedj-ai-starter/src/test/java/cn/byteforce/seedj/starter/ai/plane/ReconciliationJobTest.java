package cn.byteforce.seedj.starter.ai.plane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 全量对账：有 intent 无 outcome 补一条 outcome_missing，且不重复。 */
class ReconciliationJobTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF = UUID.fromString("018f2e3a-0000-7000-8000-000000000118");

    @Test
    @DisplayName("pendingOutcomes 补 outcome_missing，第二次不重复")
    void pendingOutcomesReconciledOnce() {
        InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();
        UUID decisionId = UUID.randomUUID();
        ledger.appendIntent(intent(decisionId));
        ReconciliationJob job =
                new ReconciliationJob(
                        mock(DataSource.class), ledger, before -> List.of(), properties());

        int processed = job.reconcileOnce(Instant.now().plus(Duration.ofHours(1)));

        assertThat(processed).isGreaterThanOrEqualTo(1);
        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.RECONCILED)
                .singleElement()
                .satisfies(
                        record ->
                                assertThat(record.body().get("reason"))
                                        .isEqualTo(ReconciliationJob.REASON_OUTCOME_MISSING));

        job.reconcileOnce(Instant.now().plus(Duration.ofHours(2)));
        assertThat(ledger.byDecision(TENANT, decisionId))
                .filteredOn(record -> record.kind() == DecisionRecordKind.RECONCILED)
                .hasSize(1);
    }

    private static DecisionIntent intent(UUID decisionId) {
        return new DecisionIntent(
                decisionId,
                TENANT,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                new CommandRef("ticket.create", List.of(), null),
                "sha256:a",
                "f".repeat(64),
                Map.of(),
                "reason",
                List.<Evidence>of(),
                new ModelRef("m", "1", null, null),
                PolicyRef.none(),
                new AgentIdentity(ACTOR, ON_BEHALF, null, "svc"),
                null);
    }

    private static AiProperties properties() {
        return new AiProperties(
                true,
                new AiProperties.Plane(true, TENANT, "agent:decide", null, Duration.ofMinutes(10)),
                new AiProperties.Guard(false),
                null,
                new AiProperties.Identity(ACTOR.toString(), ON_BEHALF.toString()),
                new AiProperties.Decision(null, "kid", Duration.ofSeconds(300), "ticket-service"),
                List.of(),
                List.of());
    }
}
