package cn.byteforce.seedj.starter.ai.plane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 决策面的授权：{@code POST /v1/decisions} 与 {@code GET /v1/decisions/{id}} 只放行带 {@code
 * seedj.ai.plane.permission} 权限的调用方。
 *
 * <p>只认证不授权是真实漏洞：任何已认证的 IAM 用户都能以配置的 agent 身份拿一枚可执行写的 token。这里用真的 {@link
 * DecisionService}（内存账本）与三个调用方视图钉住「无权限 403、无认证 401、有权限正常」，不启 web 上下文。
 */
class DecisionControllerTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID CONTRACT = UUID.fromString("018f2e3a-0000-7000-8000-00000000c017");
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-0000-7000-8000-0000000e0001");
    private static final String COMMAND_TYPE = "ticket.create";
    private static final String PERMISSION = "agent:decide";
    private static final Instant NOW = Instant.parse("2026-11-17T09:41:00Z");

    private final InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();

    private final DecisionService service = service();

    @Test
    @DisplayName("没有 agent:decide 权限的已认证调用方：求值与读记录都是 403")
    void caller_without_permission_is_forbidden() {
        DecisionController controller = controller(caller(Set.of("ticket:read")));

        assertForbidden(() -> controller.decide(executeTool()));
        assertForbidden(() -> controller.records(UUID.randomUUID()));
    }

    @Test
    @DisplayName("没有已认证调用方：401 unauthenticated")
    void unauthenticated_caller_is_rejected() {
        DecisionController controller =
                new DecisionController(service, null, Optional::empty, PERMISSION);

        assertThatThrownBy(() -> controller.decide(executeTool()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.UNAUTHENTICATED);
                        });
    }

    @Test
    @DisplayName("带 agent:decide 权限：求值放行并签发 token，读记录返回 intent")
    void caller_with_permission_decides_and_reads_records() {
        DecisionController controller = controller(caller(Set.of(PERMISSION)));

        DecisionService.DecisionResult result = controller.decide(executeTool());

        assertThat(result.verdict()).isEqualTo(DecisionService.VERDICT_ALLOW);
        assertThat(result.token()).isNotBlank();
        DecisionController.DecisionRecords records = controller.records(result.decisionId());
        assertThat(records.decisionId()).isEqualTo(result.decisionId());
        assertThat(records.records())
                .singleElement()
                .satisfies(view -> assertThat(view.kind()).isEqualTo("intent"));
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN);
                            assertThat(error.reason()).contains("seedj.ai.plane.permission");
                        });
    }

    private DecisionController controller(Caller caller) {
        CallerProvider callers = () -> Optional.of(caller);
        return new DecisionController(service, null, callers, PERMISSION);
    }

    private static Caller caller(Set<String> permissions) {
        return new Caller(
                ACTOR.toString(),
                "agent-runtime",
                TENANT.toString(),
                Set.of("agent"),
                permissions,
                Set.of(),
                Set.of(),
                null,
                null);
    }

    private DecisionService service() {
        ConfiguredToolRegistry registry =
                new ConfiguredToolRegistry(
                        List.of(new AgentToolSpec(COMMAND_TYPE, ActionClass.COMPENSABLE)));
        return new DecisionService(
                new CapabilityRegistry(
                        List.of(
                                new AiProperties.Contract(
                                        CONTRACT.toString(), List.of(COMMAND_TYPE))),
                        registry),
                registry,
                ledger,
                claims -> "jws-" + claims.decisionId(),
                UUID::randomUUID,
                () -> NOW,
                properties());
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
                        PERMISSION),
                List.of(),
                List.of());
    }

    private static DecisionService.DecisionRequest executeTool() {
        return new DecisionService.DecisionRequest(
                CONTRACT,
                CONVERSATION,
                1,
                "execute_tool",
                new DecisionService.Command(
                        COMMAND_TYPE, Map.of("title", "printer down"), List.of(), null),
                "为本次故障建一张工单",
                List.of(),
                new DecisionService.Model("pilot-model", "1", null, null),
                PERMISSION);
    }
}
