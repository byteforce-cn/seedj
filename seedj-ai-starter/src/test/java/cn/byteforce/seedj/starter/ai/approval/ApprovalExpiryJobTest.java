package cn.byteforce.seedj.starter.ai.approval;

import static org.assertj.core.api.Assertions.assertThatCode;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.infra.local.workflow.InMemoryWorkflowProvider;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 过期扫描的调度入口：当前实现是 no-op，但不能抛。 */
class ApprovalExpiryJobTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF = UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    @DisplayName("run 只驱动一次扫描，失败不外抛")
    void runDrivesSweepWithoutThrowing() {
        AiProperties ai =
                new AiProperties(
                        true,
                        new AiProperties.Plane(
                                true, TENANT, "agent:decide", null, Duration.ofMinutes(10)),
                        new AiProperties.Guard(false),
                        null,
                        new AiProperties.Identity(ACTOR.toString(), ON_BEHALF.toString()),
                        new AiProperties.Decision(
                                null, "kid", Duration.ofSeconds(300), "ticket-service"),
                        List.of(),
                        List.of());
        ApprovalProperties props =
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
                        java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                        () -> UUID.randomUUID().toString());
        ApprovalService service =
                new ApprovalService(
                        workflows,
                        new InMemoryDecisionLedger(),
                        new DecisionTokenIssuer(ai.decision()),
                        () -> NOW,
                        props,
                        ai,
                        new LoggingApprovalNotifier());
        Clock clock = () -> NOW;
        ApprovalExpiryJob job = new ApprovalExpiryJob(service, clock);

        assertThatCode(job::run).doesNotThrowAnyException();
    }
}
