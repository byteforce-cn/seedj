package cn.byteforce.seedj.iam.policy;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamResourceAttributeResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 默认 PDP 的条件求值与审计语义：运算符、操作数来源、资源模式、审计失败不影响判定。 */
class DefaultIamPolicyDecisionPointOperatorsTest {

    private final Map<String, IamPolicy> policies = new LinkedHashMap<>();
    private final List<SecurityAuditEvent> audited = new ArrayList<>();
    private final IamPolicyRepository repository =
            new IamPolicyRepository() {
                @Override
                public IamPolicy save(IamPolicy policy) {
                    policies.put(policy.id(), policy);
                    return policy;
                }

                @Override
                public Optional<IamPolicy> findById(String id) {
                    return Optional.ofNullable(policies.get(id));
                }

                @Override
                public List<IamPolicy> findEnabled(String tenantId) {
                    return new ArrayList<>(policies.values());
                }

                @Override
                public List<IamPolicy> findAll(String tenantId) {
                    return new ArrayList<>(policies.values());
                }

                @Override
                public void delete(String id) {
                    policies.remove(id);
                }

                @Override
                public long currentEpoch(String tenantId) {
                    return 0;
                }

                @Override
                public void bumpEpoch(String tenantId) {}
            };

    private final IamSubject subject =
            new IamSubject(
                    "u-1",
                    "alice",
                    "default",
                    Set.of("sales", "reader"),
                    Set.of("order:read"),
                    Set.of("g-1"),
                    Set.of("pwd", "totp"),
                    "urn:seedj:acr:totp",
                    Map.of("department", "sales"));

    private final IamResourceAttributeResolver resolver =
            new IamResourceAttributeResolver() {
                @Override
                public boolean supports(String resourceType) {
                    return "order".equals(resourceType);
                }

                @Override
                public Map<String, Object> resolve(
                        String resourceType, String resourceId, IamSubject subject) {
                    return Map.of("ownerId", "u-1", "status", "OPEN", "amount", 120);
                }
            };

    @Test
    void evaluatesOrNotAndExistsOperands() {
        allow(
                "or",
                new PolicyCondition.Or(
                        List.of(
                                compare(
                                        PolicyOperand.subject("department"),
                                        PolicyComparisonOperator.EQ,
                                        "nope"),
                                compare(
                                        PolicyOperand.action(),
                                        PolicyComparisonOperator.STARTS_WITH,
                                        "order:"))));
        assertThat(decide().allowed()).isTrue();

        allow(
                "not",
                new PolicyCondition.Not(
                        compare(
                                PolicyOperand.subject("department"),
                                PolicyComparisonOperator.EQ,
                                "sales")));
        assertThat(decide().allowed()).isFalse();

        allow("exists", new PolicyCondition.Exists(PolicyOperand.resource("ownerId")));
        assertThat(decide().allowed()).isTrue();

        allow("missing", new PolicyCondition.Exists(PolicyOperand.resource("missing")));
        assertThat(decide().allowed()).isFalse();
    }

    @Test
    void resolvesEverySubjectPath() {
        allow(
                "userId",
                compare(PolicyOperand.subject("userId"), PolicyComparisonOperator.EQ, "u-1"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "tenant",
                compare(
                        PolicyOperand.subject("tenant_id"),
                        PolicyComparisonOperator.EQ,
                        "default"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "roles",
                compare(
                        PolicyOperand.subject("roles"),
                        PolicyComparisonOperator.CONTAINS,
                        "sales"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "permissions",
                compare(
                        PolicyOperand.subject("permissions"),
                        PolicyComparisonOperator.CONTAINS,
                        "order:read"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "groups",
                compare(PolicyOperand.subject("groups"), PolicyComparisonOperator.CONTAINS, "g-1"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "amr",
                compare(PolicyOperand.subject("amr"), PolicyComparisonOperator.CONTAINS, "totp"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "acr",
                compare(
                        PolicyOperand.subject("acr"),
                        PolicyComparisonOperator.EQ,
                        "urn:seedj:acr:totp"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "attribute",
                compare(PolicyOperand.subject("department"), PolicyComparisonOperator.EQ, "sales"));
        assertThat(decide().allowed()).isTrue();
    }

    @Test
    void resolvesActionAndEnvironmentOperands() {
        allow("action", compare(PolicyOperand.action(), PolicyComparisonOperator.EQ, "order:read"));
        assertThat(decide().allowed()).isTrue();

        allow(
                "environment",
                compare(PolicyOperand.environment("ip"), PolicyComparisonOperator.EQ, "10.0.0.1"));
        assertThat(decide().allowed()).isTrue();
    }

    @Test
    void supportsEveryComparisonOperator() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("status"),
                                        PolicyComparisonOperator.NE,
                                        "CLOSED")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal("PAID"),
                                        PolicyComparisonOperator.IN,
                                        "OPEN,PAID")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("status"),
                                        PolicyComparisonOperator.IN,
                                        List.of("OPEN", "PAID"))))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(List.of("a", "b")),
                                        PolicyComparisonOperator.CONTAINS,
                                        "b")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal("employee-read"),
                                        PolicyComparisonOperator.CONTAINS,
                                        "read")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal("employee-read"),
                                        PolicyComparisonOperator.STARTS_WITH,
                                        "employee")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal("employee-read"),
                                        PolicyComparisonOperator.ENDS_WITH,
                                        "read")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("amount"),
                                        PolicyComparisonOperator.GT,
                                        100)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("amount"),
                                        PolicyComparisonOperator.LT,
                                        200)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("amount"),
                                        PolicyComparisonOperator.GE,
                                        120)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("amount"),
                                        PolicyComparisonOperator.LE,
                                        119)))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(
                                                Instant.parse("2026-11-06T00:00:00Z")),
                                        PolicyComparisonOperator.LT,
                                        Instant.parse("2026-11-07T00:00:00Z"))))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal("abc"),
                                        PolicyComparisonOperator.GT,
                                        "abb")))
                .isTrue();
    }

    @Test
    void nullOperandsNeverSatisfyOrderingComparisons() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.GT,
                                        1)))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.STARTS_WITH,
                                        "x")))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.CONTAINS,
                                        "x")))
                .isFalse();
        assertThat(
                        allows(
                                new PolicyCondition.Compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.EQ,
                                        PolicyOperand.resource("missing"))))
                .isTrue();
    }

    @Test
    void matchesResourceTypeIdAndIdPatterns() {
        allowWithResources("type", Set.of("order"));
        assertThat(decide().allowed()).isTrue();

        allowWithResources("type-id", Set.of("order:o-1"));
        assertThat(decide().allowed()).isTrue();

        allowWithResources("id", Set.of("o-1"));
        assertThat(decide().allowed()).isTrue();

        allowWithResources("wildcard", Set.of("*"));
        assertThat(decide().allowed()).isTrue();

        allowWithResources("prefix", Set.of("ord*"));
        assertThat(decide().allowed()).isTrue();

        allowWithResources("miss", Set.of("invoice"));
        assertThat(decide().allowed()).isFalse();
    }

    @Test
    void explicitAttributesOverrideResolvers() {
        allow(
                "override",
                compare(PolicyOperand.resource("status"), PolicyComparisonOperator.EQ, "CLOSED"));

        IamAuthorizationDecision decision =
                pdp().decide(
                                new IamAuthorizationRequest(
                                        subject,
                                        "order:read",
                                        "order",
                                        "o-1",
                                        Map.of("status", "CLOSED"),
                                        Map.of()));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void missingResolversAndUnsupportedTypesLeaveOnlyTypeAndId() {
        DefaultIamPolicyDecisionPoint withoutResolvers =
                new DefaultIamPolicyDecisionPoint(repository, null, null, () -> "audit-1");
        policies.put(
                "id",
                new IamPolicy(
                        "id",
                        "default",
                        "id",
                        IamPolicyEffect.ALLOW,
                        0,
                        Set.of("order:read"),
                        Set.of("o-1"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));

        assertThat(
                        withoutResolvers
                                .decide(
                                        new IamAuthorizationRequest(
                                                subject,
                                                "order:read",
                                                "order",
                                                "o-1",
                                                Map.of(),
                                                Map.of()))
                                .allowed())
                .isTrue();
    }

    @Test
    void deniesAndAuditsWhenNoPolicyMatches() {
        IamAuthorizationDecision decision =
                pdp().decide(
                                new IamAuthorizationRequest(
                                        subject, "order:read", "order", "o-1", Map.of(), Map.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("no matching allow policy");
        assertThat(audited)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.outcome()).isEqualTo("DENY");
                            assertThat(event.action()).isEqualTo("abac.order:read");
                        });
    }

    @Test
    void auditsTheDenyingPolicy() {
        repository.save(
                new IamPolicy(
                        "deny",
                        "default",
                        "deny",
                        IamPolicyEffect.DENY,
                        0,
                        Set.of("order:read"),
                        Set.of("order"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));

        assertThat(decide().allowed()).isFalse();
        assertThat(audited)
                .singleElement()
                .extracting(SecurityAuditEvent::reason)
                .isEqualTo("policy deny");
    }

    @Test
    void auditFailuresNeverChangeTheDecision() {
        DefaultIamPolicyDecisionPoint failing =
                new DefaultIamPolicyDecisionPoint(
                        repository,
                        List.of(resolver),
                        (SecurityAuditSink)
                                event -> {
                                    throw new IllegalStateException("sink down");
                                },
                        () -> "audit-1");

        IamAuthorizationDecision decision =
                failing.decide(
                        new IamAuthorizationRequest(
                                subject, "order:read", "order", "o-1", Map.of(), Map.of()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("no matching allow policy");
    }

    @Test
    void highestPriorityAllowIsReported() {
        policies.clear();
        audited.clear();
        repository.save(
                new IamPolicy(
                        "low",
                        "default",
                        "low",
                        IamPolicyEffect.ALLOW,
                        1,
                        Set.of("order:read"),
                        Set.of("order"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));
        repository.save(
                new IamPolicy(
                        "high",
                        "default",
                        "high",
                        IamPolicyEffect.ALLOW,
                        9,
                        Set.of("order:read"),
                        Set.of("order"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));

        assertThat(decide().policyId()).isEqualTo("high");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private DefaultIamPolicyDecisionPoint pdp() {
        return new DefaultIamPolicyDecisionPoint(
                repository, List.of(resolver), audited::add, () -> "audit-1");
    }

    private IamAuthorizationDecision decide() {
        return pdp().decide(
                        new IamAuthorizationRequest(
                                subject,
                                "order:read",
                                "order",
                                "o-1",
                                Map.of(),
                                Map.of("ip", "10.0.0.1")));
    }

    private boolean allows(PolicyCondition condition) {
        allow("probe", condition);
        return decide().allowed();
    }

    private void allow(String id, PolicyCondition condition) {
        policies.clear();
        audited.clear();
        repository.save(
                new IamPolicy(
                        id,
                        "default",
                        id,
                        IamPolicyEffect.ALLOW,
                        0,
                        Set.of("order:read"),
                        Set.of("order"),
                        condition,
                        true,
                        0));
    }

    private void allowWithResources(String id, Set<String> resources) {
        policies.clear();
        audited.clear();
        repository.save(
                new IamPolicy(
                        id,
                        "default",
                        id,
                        IamPolicyEffect.ALLOW,
                        0,
                        Set.of("order:read"),
                        resources,
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));
    }

    private static PolicyCondition compare(
            PolicyOperand left, PolicyComparisonOperator operator, Object right) {
        return new PolicyCondition.Compare(left, operator, PolicyOperand.literal(right));
    }
}
