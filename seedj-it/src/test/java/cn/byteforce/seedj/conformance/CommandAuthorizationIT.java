package cn.byteforce.seedj.conformance;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.AttributeSnapshot;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamCondition;
import cn.byteforce.seedj.iam.authz.IamConditionOperator;
import cn.byteforce.seedj.iam.authz.IamConditionTerm;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import cn.byteforce.seedj.starter.iam.abac.CompositeAuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.authz.IamContractAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令路径的 SoD / ABAC / 撤销权限行为：JWT claim 不再是授权事实源。
 */
class CommandAuthorizationIT {

    private static final Instant AT = Instant.parse("2026-10-15T12:00:00Z");

    private static final Clock CLOCK = () -> AT;

    private final InMemoryIamBindingStore bindings = new InMemoryIamBindingStore();

    private final InMemoryIamAuthorizationRepository roles =
            new InMemoryIamAuthorizationRepository();

    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    private final List<SecurityAuditEvent> audits = new CopyOnWriteArrayList<>();

    private CommandPolicy policy;

    @BeforeEach
    void setUp() {
        roles.createRole(IamRole.create("r-requester", "default", "requester", "申请人", "",
                null, false));
        roles.createRole(IamRole.create("r-approver", "default", "approver", "审批人", "",
                null, false));
        roles.grantPermission("r-requester", "purchase.request.create");
        roles.grantPermission("r-approver", "purchase.request.approve");

        bindings.clear();
        attributes.clear();
        audits.clear();
        bindings.save(new IamBinding("b-sod-create", "sod-user", "r-requester",
                Map.of("department", "D001"), AT, null, AT, null));
        bindings.save(new IamBinding("b-sod-approve", "sod-user", "r-approver",
                Map.of("department", "D001"), AT, null, AT, null));
        bindings.save(new IamBinding("b-grade", "grade-user", "r-approver",
                Map.of("department", "D001"), AT, null, AT, null));
        bindings.save(new IamBinding("b-missing", "no-snapshot", "r-approver",
                Map.of("department", "D001"), AT, null, AT, null));
        attributes.save(new AttributeSnapshot("grade-user", AT.minusSeconds(3600), AT,
                AT.plusSeconds(86_400), "hr", "1.0.0",
                Map.of("primary_position", Map.of("grade", "M2"))));

        IamCondition grade = new IamCondition("all", List.of(new IamConditionTerm(
                "primary_position.grade", IamConditionOperator.EQ, List.of("P5"))));
        IamContractAuthorizationService contract = new IamContractAuthorizationService(
                bindings, roles, attributes,
                List.of(new IamPermissionCondition("purchase.request.approve", grade)),
                SodRuleSet.defaults());
        policy = CommandPolicy.decisionPoint(new CompositeAuthorizationDecisionPoint(contract,
                null), CLOCK, audits::add);
    }

    private static CommandDescriptor approve() {
        return CommandDescriptor.builder()
                .type("purchase.request.approve")
                .objectType("purchase_request")
                .payloadType(Map.class)
                .permission("purchase.request.approve")
                .build();
    }

    private static Caller caller(String id, String... permissions) {
        return new Caller(id, id, "default", Set.of(), Set.of(permissions), Set.of(),
                Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());
    }

    @Test
    @DisplayName("SoD：同一主体同时拥有申请与审批 → 403 SOD-001，并写审计")
    void sodConflictDeniesCommandAndAudits() {
        Caller caller = caller("sod-user", "purchase.request.create",
                "purchase.request.approve");

        assertThatThrownBy(() -> policy.authorize(approve(), caller, Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN);
                    assertThat(error.reasonCode()).isEqualTo("SOD-001");
                    assertThat(error.details()).containsEntry("reason_code", "SOD-001");
                });
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(SecurityAuditEvent.OUTCOME_DENIED);
            assertThat(event.reason()).contains("SOD-001");
            assertThat(event.details()).containsEntry("reason_code", "SOD-001");
            assertThat(event.details()).containsEntry("command_type",
                    "purchase.request.approve");
        });
    }

    @Test
    @DisplayName("ABAC：属性等级 M2 不满足 P5 → 403 ATTRIBUTE_MISMATCH")
    void attributeMismatchDeniesCommand() {
        assertThatThrownBy(() -> policy.authorize(approve(),
                caller("grade-user", "purchase.request.approve"), Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.reasonCode()).isEqualTo("ATTRIBUTE_MISMATCH");
                    assertThat(error.reason()).contains("primary_position.grade");
                });
        assertThat(audits).singleElement().satisfies(event ->
                assertThat(event.details()).containsEntry("reason_code",
                        "ATTRIBUTE_MISMATCH"));
    }

    @Test
    @DisplayName("属性快照缺失 → 403 ATTRIBUTE_MISSING，不放行")
    void missingAttributeSnapshotFailsClosed() {
        assertThatThrownBy(() -> policy.authorize(approve(),
                caller("no-snapshot", "purchase.request.approve"), Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.reasonCode()).isEqualTo("ATTRIBUTE_MISSING");
                });
    }

    @Test
    @DisplayName("JWT 仍含权限但 IAM 已撤销 → 403 NO_PERMISSION")
    void revokedPermissionFailsEvenWhenJwtClaimStillPresent() {
        assertThatThrownBy(() -> policy.authorize(approve(),
                caller("revoked", "purchase.request.approve"), Map.of()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.reasonCode()).isEqualTo("NO_PERMISSION"));
    }
}
