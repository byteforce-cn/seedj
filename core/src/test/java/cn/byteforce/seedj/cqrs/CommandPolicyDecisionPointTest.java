package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 命令授权只通过决策点，拒绝原因码进入契约错误。 */
class CommandPolicyDecisionPointTest {

    private static final Clock CLOCK = () -> Instant.parse("2026-11-07T09:00:00Z");

    private static CommandDescriptor descriptor() {
        return CommandDescriptor.builder()
                .type("hr.employee.update")
                .objectType("employee")
                .payloadType(String.class)
                .permission("hr.employee.update")
                .build();
    }

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", Set.of(),
                Set.of("hr.employee.update"), Set.of(), Set.of("pwd"),
                "urn:seedj:acr:pwd", Map.of());
    }

    @Test
    @DisplayName("deny 映射为 403 并携带 reason_code")
    void deniedDecisionBecomesForbiddenWithReasonCode() {
        AuthorizationDecisionPoint denyAll = request ->
                AuthorizationDecision.deny("SOD-001", "conflict", "sod");
        CommandPolicy policy = CommandPolicy.decisionPoint(denyAll, CLOCK);

        assertThatThrownBy(() -> policy.authorize(descriptor(), caller(), Map.of()))
                .isInstanceOf(ContractException.class)
                .satisfies(error -> {
                    ContractException contract = (ContractException) error;
                    assertThat(contract.code()).isEqualTo(ContractErrorCode.FORBIDDEN);
                    assertThat(contract.reasonCode()).isEqualTo("SOD-001");
                    assertThat(contract.details()).containsEntry("reason_code", "SOD-001");
                });
    }

    @Test
    @DisplayName("allow 放行")
    void allowedDecisionPasses() {
        CommandPolicy policy = CommandPolicy.decisionPoint(
                request -> AuthorizationDecision.allow("rbac"), CLOCK);

        policy.authorize(descriptor(), caller(), Map.of());
    }

    @Test
    @DisplayName("请求使用命令权限与对象类型，动作不是调用方自报")
    void requestUsesDescriptorPermissionAndObjectType() {
        AuthorizationDecision[] seen = new AuthorizationDecision[1];
        CommandPolicy policy = CommandPolicy.decisionPoint(request -> {
            assertThat(request.action()).isEqualTo("hr.employee.update");
            assertThat(request.resourceType()).isEqualTo("employee");
            assertThat(request.resourceId()).isNull();
            assertThat(request.asOf()).isEqualTo(CLOCK.now());
            seen[0] = AuthorizationDecision.allow("ok");
            return seen[0];
        }, CLOCK);

        policy.authorize(descriptor(), caller(), Map.of());
        assertThat(seen[0]).isNotNull();
    }

    @Test
    @DisplayName("描述缺少权限是装配错误")
    void missingPermissionFailsAtAssembly() {
        CommandDescriptor descriptor = CommandDescriptor.builder()
                .type("hr.employee.update")
                .objectType("employee")
                .payloadType(String.class)
                .build();
        CommandPolicy policy = CommandPolicy.decisionPoint(
                request -> AuthorizationDecision.allow("unused"), CLOCK);

        assertThatThrownBy(() -> policy.authorize(descriptor, caller(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    @DisplayName("拒绝写入安全审计；审计故障不改变 403")
    void denialIsAuditedAndAuditFailureIsIgnored() {
        List<SecurityAuditEvent> events = new ArrayList<>();
        SecurityAuditSink audit = events::add;
        CommandPolicy policy = CommandPolicy.decisionPoint(
                request -> AuthorizationDecision.deny("SOD-001", "conflict", "sod"),
                CLOCK, audit);

        assertThatThrownBy(() -> policy.authorize(descriptor(), caller(), Map.of()))
                .isInstanceOf(ContractException.class);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(SecurityAuditEvent.OUTCOME_DENIED);
            assertThat(event.details()).containsEntry("reason_code", "SOD-001");
        });

        SecurityAuditSink broken = event -> {
            throw new IllegalStateException("audit down");
        };
        CommandPolicy withBrokenAudit = CommandPolicy.decisionPoint(
                request -> AuthorizationDecision.deny("SOD-001", "conflict", "sod"),
                CLOCK, broken);
        assertThatThrownBy(() -> withBrokenAudit.authorize(descriptor(), caller(), Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("决策点返回 null 是装配缺陷")
    void nullDecisionIsAnError() {
        CommandPolicy policy = CommandPolicy.decisionPoint(request -> null, CLOCK);

        assertThatThrownBy(() -> policy.authorize(descriptor(), caller(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned null");
    }

    @Test
    @DisplayName("未认证调用方被拒绝")
    void missingCallerIsUnauthenticated() {
        CommandPolicy policy = CommandPolicy.decisionPoint(
                request -> AuthorizationDecision.allow("unused"), CLOCK);

        assertThatThrownBy(() -> policy.authorize(descriptor(), null, Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.UNAUTHENTICATED));
    }
}
