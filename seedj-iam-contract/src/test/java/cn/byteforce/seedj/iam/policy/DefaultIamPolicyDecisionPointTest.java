package cn.byteforce.seedj.iam.policy;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamResourceAttributeResolver;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationRequest;
import cn.byteforce.seedj.security.AuthorizationSubject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultIamPolicyDecisionPointTest {

    private static final java.time.Instant AS_OF = java.time.Instant.parse("2026-11-21T00:00:00Z");

    private final InMemoryPolicyRepository repository = new InMemoryPolicyRepository();
    private final DefaultIamPolicyDecisionPoint pdp =
            new DefaultIamPolicyDecisionPoint(
                    repository, List.of(new OwnerResolver()), null, () -> "audit-1");

    private final AuthorizationSubject subject =
            new AuthorizationSubject(
                    "u-1",
                    "alice",
                    "default",
                    Set.of("sales"),
                    Set.of("order:read"),
                    Set.of(),
                    Set.of("pwd"),
                    "urn:seedj:acr:pwd",
                    Map.of("department", "sales"));

    @Test
    void allowsWhenPolicyMatchesAndResourceResolverProvidesOwner() {
        repository.save(
                new IamPolicy(
                        "p1",
                        "default",
                        "own orders",
                        IamPolicyEffect.ALLOW,
                        10,
                        Set.of("order:read"),
                        Set.of("order"),
                        new PolicyCondition.Compare(
                                PolicyOperand.resource("ownerId"),
                                PolicyComparisonOperator.EQ,
                                PolicyOperand.subject("userId")),
                        true,
                        0));

        AuthorizationDecision decision = pdp.decide(request("order", "o-1"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyId()).isEqualTo("p1");
    }

    @Test
    void deniesByDefaultWhenNoPolicyMatches() {
        assertThat(pdp.decide(request("order", "o-1")).allowed()).isFalse();
    }

    @Test
    void denyWinsOverAllowRegardlessOfPriority() {
        repository.save(
                new IamPolicy(
                        "allow",
                        "default",
                        "allow",
                        IamPolicyEffect.ALLOW,
                        1000,
                        Set.of("order:read"),
                        Set.of("order"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));
        repository.save(
                new IamPolicy(
                        "deny",
                        "default",
                        "deny",
                        IamPolicyEffect.DENY,
                        1,
                        Set.of("order:read"),
                        Set.of("order"),
                        PolicyCondition.Always.INSTANCE,
                        true,
                        0));

        AuthorizationDecision decision = pdp.decide(request("order", "o-1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("deny");
    }

    @Test
    void evaluatesTypedOperatorsAndNestedConditions() {
        repository.save(
                new IamPolicy(
                        "p1",
                        "default",
                        "sales",
                        IamPolicyEffect.ALLOW,
                        10,
                        Set.of("order:*"),
                        Set.of("order"),
                        new PolicyCondition.And(
                                List.of(
                                        new PolicyCondition.Compare(
                                                PolicyOperand.subject("department"),
                                                PolicyComparisonOperator.EQ,
                                                PolicyOperand.literal("sales")),
                                        new PolicyCondition.Compare(
                                                PolicyOperand.resource("status"),
                                                PolicyComparisonOperator.IN,
                                                PolicyOperand.literal(List.of("OPEN", "PAID"))))),
                        true,
                        0));

        assertThat(pdp.decide(request("order", "o-1")).allowed()).isTrue();
        assertThat(
                        pdp.decide(
                                        new AuthorizationRequest(
                                                subject,
                                                "order:read",
                                                "order",
                                                "o-1",
                                                Map.of("status", "CLOSED"),
                                                AS_OF,
                                                Map.of()))
                                .allowed())
                .isFalse();
    }

    private AuthorizationRequest request(String type, String id) {
        return new AuthorizationRequest(subject, "order:read", type, id, Map.of(), AS_OF, Map.of());
    }

    private static final class OwnerResolver implements IamResourceAttributeResolver {
        @Override
        public boolean supports(String resourceType) {
            return "order".equals(resourceType);
        }

        @Override
        public Map<String, Object> resolve(
                String resourceType, String resourceId, AuthorizationSubject subject) {
            return Map.of("ownerId", "u-1", "status", "OPEN");
        }
    }

    private static final class InMemoryPolicyRepository implements IamPolicyRepository {
        private final Map<String, IamPolicy> policies = new LinkedHashMap<>();

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
    }
}
