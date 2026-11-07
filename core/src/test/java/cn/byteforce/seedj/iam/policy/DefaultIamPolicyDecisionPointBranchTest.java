package cn.byteforce.seedj.iam.policy;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamAuthorizationRequest;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.IamSubject;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 默认 PDP 的剩余求值分支：username 路径、非集合 IN、数值相等、集合相等与 跨类型排序比较。 */
class DefaultIamPolicyDecisionPointBranchTest {

    private final Map<String, IamPolicy> policies = new LinkedHashMap<>();

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
                    Set.of("sales"),
                    Set.of("order:read"),
                    Set.of("g-1"),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of("department", "sales"));

    @Test
    void resolvesUsernamePath() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("username"),
                                        PolicyComparisonOperator.EQ,
                                        "alice")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("username"),
                                        PolicyComparisonOperator.EQ,
                                        "bob")))
                .isFalse();
    }

    @Test
    void resolvesUserIdAlias() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("sub"),
                                        PolicyComparisonOperator.EQ,
                                        "u-1")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("tenantId"),
                                        PolicyComparisonOperator.EQ,
                                        "default")))
                .isTrue();
    }

    @Test
    void inOperatorWithoutCollectionOrStringRightSideNeverMatches() {
        assertThat(allows(compare(PolicyOperand.literal("OPEN"), PolicyComparisonOperator.IN, 5)))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.IN,
                                        "OPEN,PAID")))
                .isFalse();
    }

    @Test
    void equalComparesNumbersNumerically() {
        assertThat(allows(compare(PolicyOperand.literal(120), PolicyComparisonOperator.EQ, 120.0)))
                .isTrue();
        assertThat(allows(compare(PolicyOperand.literal(120), PolicyComparisonOperator.EQ, 121)))
                .isFalse();
    }

    @Test
    void equalUnwrapsCollectionsOnTheLeft() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("roles"),
                                        PolicyComparisonOperator.EQ,
                                        "sales")))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("roles"),
                                        PolicyComparisonOperator.EQ,
                                        "finance")))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.subject("roles"),
                                        PolicyComparisonOperator.NE,
                                        "finance")))
                .isTrue();
    }

    @Test
    void orderingComparisonFallsBackToStringOrderForIncompatibleTypes() {
        Instant asOf = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(allows(compare(PolicyOperand.literal(asOf), PolicyComparisonOperator.LT, "abc")))
                .isTrue();
        assertThat(allows(compare(PolicyOperand.literal(asOf), PolicyComparisonOperator.GT, "abc")))
                .isFalse();
    }

    @Test
    void orderingComparisonUsesNaturalOrderForSameType() {
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-02-01T00:00:00Z");

        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(later),
                                        PolicyComparisonOperator.GT,
                                        earlier)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(earlier),
                                        PolicyComparisonOperator.LT,
                                        later)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(later),
                                        PolicyComparisonOperator.GE,
                                        later)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(earlier),
                                        PolicyComparisonOperator.LE,
                                        later)))
                .isTrue();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(earlier),
                                        PolicyComparisonOperator.GE,
                                        later)))
                .isFalse();
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.literal(later),
                                        PolicyComparisonOperator.LE,
                                        earlier)))
                .isFalse();
    }

    @Test
    void endsWithRejectsNullOperands() {
        assertThat(
                        allows(
                                compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.ENDS_WITH,
                                        "x")))
                .isFalse();
        assertThat(
                        allows(
                                new PolicyCondition.Compare(
                                        PolicyOperand.literal("abc"),
                                        PolicyComparisonOperator.ENDS_WITH,
                                        PolicyOperand.resource("missing"))))
                .isFalse();
    }

    @Test
    void equalTreatsNullAndStringFormsConsistently() {
        assertThat(
                        allows(
                                new PolicyCondition.Compare(
                                        PolicyOperand.resource("missing"),
                                        PolicyComparisonOperator.EQ,
                                        PolicyOperand.literal("x"))))
                .isFalse();
        assertThat(allows(compare(PolicyOperand.literal(120), PolicyComparisonOperator.EQ, "120")))
                .isTrue();
    }

    @Test
    void nestedNotFlipsInnerResult() {
        PolicyCondition inner =
                compare(PolicyOperand.subject("department"), PolicyComparisonOperator.EQ, "sales");

        assertThat(allows(new PolicyCondition.Not(inner))).isFalse();
        assertThat(allows(new PolicyCondition.Not(new PolicyCondition.Not(inner)))).isTrue();
        assertThat(
                        allows(
                                new PolicyCondition.And(
                                        List.of(
                                                inner,
                                                new PolicyCondition.Not(
                                                        compare(
                                                                PolicyOperand.subject("department"),
                                                                PolicyComparisonOperator.EQ,
                                                                "hr"))))))
                .isTrue();
        assertThat(
                        allows(
                                new PolicyCondition.Or(
                                        List.of(
                                                new PolicyCondition.Not(inner),
                                                compare(
                                                        PolicyOperand.subject("department"),
                                                        PolicyComparisonOperator.EQ,
                                                        "sales")))))
                .isTrue();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private boolean allows(PolicyCondition condition) {
        policies.clear();
        repository.save(
                new IamPolicy(
                        "probe",
                        "default",
                        "probe",
                        IamPolicyEffect.ALLOW,
                        0,
                        Set.of("order:read"),
                        Set.of("order"),
                        condition,
                        true,
                        0));
        IamAuthorizationDecision decision =
                new DefaultIamPolicyDecisionPoint(repository, List.of(), null, () -> "audit-1")
                        .decide(
                                new IamAuthorizationRequest(
                                        subject, "order:read", "order", "o-1", Map.of(), Map.of()));
        return decision.allowed();
    }

    private static PolicyCondition compare(
            PolicyOperand left, PolicyComparisonOperator operator, Object right) {
        return new PolicyCondition.Compare(left, operator, PolicyOperand.literal(right));
    }
}
