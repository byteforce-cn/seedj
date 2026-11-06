package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeKey;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static cn.byteforce.seedj.core.security.ScopeKeyType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyScopeCompilerTest {

    private static final ScopeKey<String> DEPARTMENT = new ScopeKey<>("department", STRING);

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of("pwd"), "urn:seedj:acr:pwd", Map.of("department", "D01"));
    }

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .contextKey(DEPARTMENT)
                .field("org_unit_id", FieldType.TEXT)
                .field("status", FieldType.TEXT)
                .field("name", FieldType.TEXT);
        return modules.freeze();
    }

    private static PolicyScopeCompiler compiler(List<IamPolicy> policies) {
        ObjectModuleRegistry registry = registry();
        return new PolicyScopeCompiler(new FakePolicies(policies), registry,
                registry.scopeKeys());
    }

    private static IamPolicy allow(String id, PolicyCondition condition) {
        return IamPolicy.allow(id, "default", id, Set.of("read"), Set.of("employee"), condition);
    }

    private static IamPolicy deny(String id, PolicyCondition condition) {
        return IamPolicy.deny(id, "default", id, Set.of("read"), Set.of("employee"), condition);
    }

    private static PolicyCondition compare(PolicyOperand left, PolicyComparisonOperator op,
                                           PolicyOperand right) {
        return new PolicyCondition.Compare(left, op, right);
    }

    @Test
    void compilesSubjectToConstant() {
        ScopePredicate predicate = compiler(List.of(allow("p1", compare(
                PolicyOperand.subject("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(), ScopeContext.empty());

        assertThat(predicate).isEqualTo(ScopePredicate.eq("org_unit_id", "D01"));
    }

    @Test
    void compilesDenyAsNot() {
        ScopePredicate predicate = compiler(List.of(
                allow("allow", compare(PolicyOperand.resource("status"),
                        PolicyComparisonOperator.EQ, PolicyOperand.literal("ACTIVE"))),
                deny("deny", compare(PolicyOperand.subject("department"),
                        PolicyComparisonOperator.EQ, PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(), ScopeContext.empty());

        assertThat(predicate).isEqualTo(ScopePredicate.and(
                ScopePredicate.eq("status", "ACTIVE"),
                ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D01"))));
    }

    @Test
    void unsupportedFieldToFieldComparisonFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("org_unit_id"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("status")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("p1");
    }

    @Test
    void unknownEnvironmentKeyFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.environment("missing"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void unknownResourceFieldFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.subject("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("missing")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void multipleAllowsBecomeOr() {
        ScopePredicate predicate = compiler(List.of(
                allow("p1", compare(PolicyOperand.resource("status"),
                        PolicyComparisonOperator.EQ, PolicyOperand.literal("ACTIVE"))),
                allow("p2", compare(PolicyOperand.resource("org_unit_id"),
                        PolicyComparisonOperator.EQ, PolicyOperand.literal("D01")))))
                .compile("employee", caller(), ScopeContext.empty());

        assertThat(predicate).isEqualTo(ScopePredicate.or(
                ScopePredicate.eq("status", "ACTIVE"),
                ScopePredicate.eq("org_unit_id", "D01")));
    }

    @Test
    void noAllowBecomesNone() {
        assertThat(compiler(List.of()).compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
        assertThat(compiler(List.of(deny("deny", compare(PolicyOperand.resource("status"),
                PolicyComparisonOperator.EQ, PolicyOperand.literal("ACTIVE")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
    }

    @Test
    void existsBecomesNotNull() {
        ScopePredicate predicate = compiler(List.of(allow("p1",
                new PolicyCondition.Exists(PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(), ScopeContext.empty());

        assertThat(predicate).isEqualTo(ScopePredicate.notNull("org_unit_id"));
    }

    @Test
    void neAndInCompile() {
        ScopePredicate predicate = compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.NE,
                PolicyOperand.literal("TERMINATED")))))
                .compile("employee", caller(), ScopeContext.empty());
        assertThat(predicate).isEqualTo(ScopePredicate.not(
                ScopePredicate.eq("status", "TERMINATED")));

        ScopePredicate in = compiler(List.of(allow("p2", compare(
                PolicyOperand.resource("org_unit_id"), PolicyComparisonOperator.IN,
                PolicyOperand.literal(List.of("D01", "D02"))))))
                .compile("employee", caller(), ScopeContext.empty());
        assertThat(in).isEqualTo(ScopePredicate.in("org_unit_id", List.of("D01", "D02")));
    }

    @Test
    void unsupportedOperatorFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.CONTAINS,
                PolicyOperand.literal("A")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("p1");
    }

    private static final class FakePolicies implements IamPolicyRepository {

        private final List<IamPolicy> policies;

        private FakePolicies(List<IamPolicy> policies) {
            this.policies = policies;
        }

        @Override
        public IamPolicy save(IamPolicy policy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IamPolicy> findById(String id) {
            return policies.stream().filter(policy -> policy.id().equals(id)).findFirst();
        }

        @Override
        public List<IamPolicy> findEnabled(String tenantId) {
            return policies.stream().filter(IamPolicy::enabled).toList();
        }

        @Override
        public List<IamPolicy> findAll(String tenantId) {
            return policies;
        }

        @Override
        public void delete(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long currentEpoch(String tenantId) {
            return 0;
        }

        @Override
        public void bumpEpoch(String tenantId) {
            throw new UnsupportedOperationException();
        }
    }
}
