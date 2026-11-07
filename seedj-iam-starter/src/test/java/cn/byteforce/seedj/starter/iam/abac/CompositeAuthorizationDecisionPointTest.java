package cn.byteforce.seedj.starter.iam.abac;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;
import cn.byteforce.seedj.starter.iam.authz.IamContractAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 组合决策点：SoD 在命令路径生效，字段读取走 ABAC。 */
class CompositeAuthorizationDecisionPointTest {

    private static final Instant AT = Instant.parse("2026-10-15T12:00:00Z");

    private final InMemoryIamBindingStore bindings = new InMemoryIamBindingStore();

    private final InMemoryIamAuthorizationRepository roles =
            new InMemoryIamAuthorizationRepository();

    private final InMemoryIamAttributeSource attributes = new InMemoryIamAttributeSource();

    private IamContractAuthorizationService contract;

    @BeforeEach
    void setUp() {
        roles.createRole(
                IamRole.create("r-requester", "default", "requester", "申请人", "", null, false));
        roles.createRole(
                IamRole.create("r-approver", "default", "approver", "审批人", "", null, false));
        roles.grantPermission("r-requester", "purchase.request.create");
        roles.grantPermission("r-approver", "purchase.request.approve");
        bindings.save(
                new IamBinding(
                        "b-create",
                        "both",
                        "r-requester",
                        Map.of("department", "D001"),
                        AT,
                        null,
                        AT,
                        null));
        bindings.save(
                new IamBinding(
                        "b-approve",
                        "both",
                        "r-approver",
                        Map.of("department", "D001"),
                        AT,
                        null,
                        AT,
                        null));
        contract =
                new IamContractAuthorizationService(
                        bindings, roles, attributes, List.of(), SodRuleSet.defaults());
    }

    private static AuthorizationSubject subject() {
        return new AuthorizationSubject(
                "both",
                "both",
                "default",
                Set.of(),
                Set.of("purchase.request.create", "purchase.request.approve"),
                Set.of(),
                Set.of("pwd"),
                "acr",
                Map.of());
    }

    private static AuthorizationRequest request(String action, Map<String, Object> resource) {
        return new AuthorizationRequest(
                subject(), action, "purchase_request", null, resource, AT, Map.of());
    }

    @Test
    @DisplayName("SoD 冲突拒绝命令权限")
    void sodConflictDeniesCommandPermission() {
        CompositeAuthorizationDecisionPoint composite =
                new CompositeAuthorizationDecisionPoint(
                        contract, request -> IamAuthorizationDecision.allow("unused"));

        AuthorizationDecision decision =
                composite.decide(request("purchase.request.approve", Map.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).startsWith("SOD-");
    }

    @Test
    @DisplayName("字段读取带 classification 时走策略引擎")
    void fieldReadWithClassificationGoesToPolicyEngine() {
        IamPolicyDecisionPoint policies = request -> IamAuthorizationDecision.allow("abac-field");
        CompositeAuthorizationDecisionPoint composite =
                new CompositeAuthorizationDecisionPoint(contract, policies);

        AuthorizationDecision decision =
                composite.decide(
                        new AuthorizationRequest(
                                subject(),
                                "read.masked",
                                "employee",
                                "contact",
                                Map.of("classification", "SENSITIVE"),
                                AT,
                                Map.of()));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyId()).isEqualTo("abac-field");
    }

    @Test
    @DisplayName("策略引擎拒绝字段读取时拒绝")
    void deniedPolicyDeniesFieldRead() {
        CompositeAuthorizationDecisionPoint composite =
                new CompositeAuthorizationDecisionPoint(
                        contract,
                        request -> IamAuthorizationDecision.deny("no matching allow policy"));

        AuthorizationDecision decision =
                composite.decide(
                        new AuthorizationRequest(
                                subject(),
                                "read",
                                "employee",
                                "contact",
                                Map.of("classification", "SENSITIVE"),
                                AT,
                                Map.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("no matching allow policy");
    }

    @Test
    @DisplayName("资源属性同时询问两个引擎：任一拒绝即拒绝")
    void commandWithResourceAttributesRequiresBothEngines() {
        CompositeAuthorizationDecisionPoint composite =
                new CompositeAuthorizationDecisionPoint(
                        contract, request -> IamAuthorizationDecision.deny("attribute mismatch"));

        AuthorizationDecision decision =
                composite.decide(
                        request("purchase.request.create", Map.of("classification", "INTERNAL")));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo("attribute mismatch");
    }

    @Test
    @DisplayName("未启用 ABAC 时仍执行契约检查（SoD 不放行）")
    void withoutPolicyEngineFallsBackToContract() {
        CompositeAuthorizationDecisionPoint composite =
                new CompositeAuthorizationDecisionPoint(contract, null);

        AuthorizationDecision decision =
                composite.decide(request("purchase.request.create", Map.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reasonCode()).startsWith("SOD-");
    }
}
