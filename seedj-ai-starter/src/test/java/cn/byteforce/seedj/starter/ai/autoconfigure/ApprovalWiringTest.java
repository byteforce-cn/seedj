package cn.byteforce.seedj.starter.ai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.infra.local.workflow.InMemoryWorkflowProvider;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.approval.ApprovalExpiryJob;
import cn.byteforce.seedj.starter.ai.approval.ApprovalNotifier;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 审批自动配置：开关、审批人与工作流端口缺一不可。 */
class ApprovalWiringTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID APPROVER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private static final ApplicationContextRunner RUNNER =
            new ApplicationContextRunner()
                    .withUserConfiguration(Host.class)
                    .withConfiguration(AutoConfigurations.of(ApprovalAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.ai.plane.tenant=" + TENANT,
                            "seedj.ai.identity.actor=" + ACTOR,
                            "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF,
                            "seedj.ai.decision.audience=ticket-service",
                            "seedj.ai.decision.ttl=300s",
                            "seedj.ai.approval.enabled=true",
                            "seedj.ai.approval.approvers=" + APPROVER);

    @Test
    @DisplayName("启用但没有 WorkflowProvider：启动失败并点名 bean")
    void enabledWithoutWorkflowProviderFailsNamingKey() {
        RUNNER.run(
                context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "seedj.ai.approval.enabled=true 需要 WorkflowProvider bean；"
                                            + "本地请加入 seedj-workflow-local 并设置"
                                            + " seedj.workflow.provider=memory");
                });
    }

    @Test
    @DisplayName("启用且有 WorkflowProvider：装配审批服务与默认通知")
    void enabledWithProviderWiresService() {
        RUNNER.withUserConfiguration(WithWorkflow.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(ApprovalService.class);
                            assertThat(context).hasSingleBean(ApprovalNotifier.class);
                            assertThat(context).hasSingleBean(ApprovalExpiryJob.class);
                        });
    }

    @Test
    @DisplayName("开关关闭：一个审批 bean 都没有")
    void disabledDoesNotWire() {
        RUNNER.withPropertyValues("seedj.ai.approval.enabled=false")
                .withUserConfiguration(WithWorkflow.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ApprovalService.class);
                        });
    }

    @Test
    @DisplayName("审批人为空：启动失败并点名配置键")
    void emptyApproversFailsNamingKey() {
        RUNNER.withPropertyValues("seedj.ai.approval.approvers=")
                .withUserConfiguration(WithWorkflow.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            Throwable root = context.getStartupFailure();
                            while (root.getCause() != null) {
                                root = root.getCause();
                            }
                            assertThat(root.getMessage()).contains("seedj.ai.approval.approvers");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class Host {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }

        @Bean
        DecisionTokenIssuer decisionTokenIssuer(AiProperties properties) {
            return new DecisionTokenIssuer(properties.decision());
        }

        @Bean
        Clock clock() {
            return () -> NOW;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithWorkflow {

        @Bean
        WorkflowProvider workflowProvider() {
            return new InMemoryWorkflowProvider(
                    java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                    () -> UUID.randomUUID().toString());
        }
    }
}
