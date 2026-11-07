package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.core.security.ScopeKey;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.IamPolicyEffect;
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

    @Test
    void unknownObjectTypeFails() {
        assertThatThrownBy(() -> compiler(List.of())
                .compile("invoice", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown object type: invoice");
    }

    @Test
    void nullCallerOrContextFails() {
        PolicyScopeCompiler compiler = compiler(List.of());

        assertThatThrownBy(() -> compiler.compile("employee", null, ScopeContext.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("caller");
        assertThatThrownBy(() -> compiler.compile("employee", caller(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    void alwaysConditionCompilesToAll() {
        assertThat(compiler(List.of(allow("p1", PolicyCondition.Always.INSTANCE)))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.all());
    }

    @Test
    void andOrNotNest() {
        PolicyCondition condition = new PolicyCondition.And(List.of(
                new PolicyCondition.Not(compare(PolicyOperand.resource("status"),
                        PolicyComparisonOperator.EQ, PolicyOperand.literal("TERMINATED"))),
                new PolicyCondition.Or(List.of(
                        compare(PolicyOperand.resource("org_unit_id"),
                                PolicyComparisonOperator.EQ, PolicyOperand.literal("D01")),
                        compare(PolicyOperand.resource("org_unit_id"),
                                PolicyComparisonOperator.EQ, PolicyOperand.literal("D02"))))));

        assertThat(compiler(List.of(allow("p1", condition)))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicates.and(List.of(
                        ScopePredicate.not(ScopePredicate.eq("status", "TERMINATED")),
                        ScopePredicates.or(List.of(
                                ScopePredicate.eq("org_unit_id", "D01"),
                                ScopePredicate.eq("org_unit_id", "D02"))))));
    }

    @Test
    void policyActionsAndResourcesMustMatchReadAndObjectType() {
        IamPolicy wrongAction = new IamPolicy("p-write", "default", "p-write",
                IamPolicyEffect.ALLOW, 0, Set.of("write"), Set.of("employee"),
                PolicyCondition.Always.INSTANCE, true, 0);
        IamPolicy wrongResource = new IamPolicy("p-invoice", "default", "p-invoice",
                IamPolicyEffect.ALLOW, 0, Set.of("read"), Set.of("invoice"),
                PolicyCondition.Always.INSTANCE, true, 0);
        IamPolicy wildcards = new IamPolicy("p-star", "default", "p-star",
                IamPolicyEffect.ALLOW, 0, Set.of("read*"), Set.of("employee*"),
                PolicyCondition.Always.INSTANCE, true, 0);

        assertThat(compiler(List.of(wrongAction, wrongResource))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
        assertThat(compiler(List.of(wildcards)).compile("employee", caller(),
                ScopeContext.empty())).isEqualTo(ScopePredicate.all());
    }

    @Test
    void disabledAndForeignTenantPoliciesAreIgnored() {
        IamPolicy disabled = allow("p1", PolicyCondition.Always.INSTANCE).withEnabled(false);
        IamPolicy foreign = IamPolicy.allow("p2", "acme", "p2", Set.of("read"),
                Set.of("employee"), PolicyCondition.Always.INSTANCE);

        assertThat(compiler(List.of(disabled, foreign))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
    }

    @Test
    void subjectPathsResolveAgainstCaller() {
        assertThat(constantEqualsSubject("userId", "u-1")).isTrue();
        assertThat(constantEqualsSubject("sub", "u-1")).isTrue();
        assertThat(constantEqualsSubject("username", "alice")).isTrue();
        assertThat(constantEqualsSubject("tenantId", "default")).isTrue();
        assertThat(constantEqualsSubject("tenant_id", "default")).isTrue();
        assertThat(constantEqualsSubject("roles", Set.of())).isTrue();
        assertThat(constantEqualsSubject("permissions", Set.of())).isTrue();
        assertThat(constantEqualsSubject("groups", Set.of())).isTrue();
        assertThat(constantEqualsSubject("amr", Set.of("pwd"))).isTrue();
        assertThat(constantEqualsSubject("acr", "urn:seedj:acr:pwd")).isTrue();
        assertThat(constantEqualsSubject("department", "D01")).isTrue();
        assertThat(constantEqualsSubject("missing", "whatever")).isFalse();
    }

    @Test
    void collectionSubjectAttributeEqualsScalarMeansMembership() {
        Caller admin = new Caller("u-1", "alice", "default", Set.of("admin"),
                Set.of("hr.read"), Set.of("g1"), Set.of("pwd"),
                "urn:seedj:acr:pwd", Map.of());

        assertThat(constantEqualsSubject(admin, "roles", "admin"))
                .isEqualTo(ScopePredicate.all());
        assertThat(constantEqualsSubject(admin, "permissions", "hr.read"))
                .isEqualTo(ScopePredicate.all());
        assertThat(constantEqualsSubject(admin, "roles", "nobody"))
                .isEqualTo(ScopePredicate.none());
        assertThat(constantCompare(admin, PolicyComparisonOperator.EQ,
                PolicyOperand.literal("admin"), PolicyOperand.subject("roles")))
                .isEqualTo(ScopePredicate.all());
    }

    @Test
    void subjectValueBecomesPredicateConstant() {
        ScopePredicate predicate = compiler(List.of(allow("p1", compare(
                PolicyOperand.subject("username"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("name")))))
                .compile("employee", caller(), ScopeContext.empty());

        assertThat(predicate).isEqualTo(ScopePredicate.eq("name", "alice"));
    }

    @Test
    void environmentFactCompiles() {
        ScopePredicate predicate = compiler(List.of(allow("p1", compare(
                PolicyOperand.environment("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(),
                        ScopeContext.of(Map.of("department", "D01")));

        assertThat(predicate).isEqualTo(ScopePredicate.eq("org_unit_id", "D01"));
    }

    @Test
    void missingEnvironmentFactBecomesNone() {
        assertThat(compiler(List.of(allow("p1", compare(
                PolicyOperand.environment("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.literal("D01")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
    }

    @Test
    void environmentFactWithWrongTypeFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.environment("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("org_unit_id")))))
                .compile("employee", caller(), ScopeContext.of(Map.of("department", 42))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has wrong type");
    }

    @Test
    void existsOnNonResourceOperandIsConstant() {
        assertThat(compiler(List.of(allow("p1",
                new PolicyCondition.Exists(PolicyOperand.subject("username")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.all());
        assertThat(compiler(List.of(allow("p1",
                new PolicyCondition.Exists(PolicyOperand.subject("missing")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
        assertThat(compiler(List.of(allow("p1",
                new PolicyCondition.Exists(PolicyOperand.environment("department")))))
                .compile("employee", caller(), ScopeContext.of(Map.of("department", "D01"))))
                .isEqualTo(ScopePredicate.all());
    }

    @Test
    void constantOnlyCompareIsFolded() {
        assertThat(constantCompare(PolicyComparisonOperator.EQ,
                PolicyOperand.literal("ACTIVE"), PolicyOperand.literal("ACTIVE")))
                .isEqualTo(ScopePredicate.all());
        assertThat(constantCompare(PolicyComparisonOperator.EQ,
                PolicyOperand.literal("ACTIVE"), PolicyOperand.literal("TERMINATED")))
                .isEqualTo(ScopePredicate.none());
        assertThat(constantCompare(PolicyComparisonOperator.NE,
                PolicyOperand.literal("ACTIVE"), PolicyOperand.literal("TERMINATED")))
                .isEqualTo(ScopePredicate.all());
        assertThat(constantCompare(PolicyComparisonOperator.IN, PolicyOperand.literal("A"),
                PolicyOperand.literal(List.of("A", "B")))).isEqualTo(ScopePredicate.all());
        assertThat(constantCompare(PolicyComparisonOperator.IN, PolicyOperand.literal("C"),
                PolicyOperand.literal(List.of("A", "B")))).isEqualTo(ScopePredicate.none());
        assertThat(constantCompare(PolicyComparisonOperator.EQ, PolicyOperand.literal(1),
                PolicyOperand.literal(1.0))).isEqualTo(ScopePredicate.all());
        assertThat(constantCompare(PolicyComparisonOperator.EQ, PolicyOperand.literal(1),
                PolicyOperand.literal("1"))).isEqualTo(ScopePredicate.all());
    }

    @Test
    void constantLeftResourceRightCompiles() {
        assertThat(compiler(List.of(allow("p1", compare(PolicyOperand.literal("ACTIVE"),
                PolicyComparisonOperator.EQ, PolicyOperand.resource("status")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.eq("status", "ACTIVE"));
        assertThat(compiler(List.of(allow("p2", compare(PolicyOperand.literal("ACTIVE"),
                PolicyComparisonOperator.NE, PolicyOperand.resource("status")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.not(ScopePredicate.eq("status", "ACTIVE")));
        assertThatThrownBy(() -> compiler(List.of(allow("p3", compare(
                PolicyOperand.literal(List.of("ACTIVE")), PolicyComparisonOperator.IN,
                PolicyOperand.resource("status")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constant IN resource field");
    }

    @Test
    void inRequiresCollectionConstant() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.IN,
                PolicyOperand.literal("ACTIVE")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN requires a collection constant");
    }

    @Test
    void actionOperandFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.EQ,
                PolicyOperand.action()))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action operand");
    }

    @Test
    void existsOnUnknownResourceFieldFails() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1",
                new PolicyCondition.Exists(PolicyOperand.resource("missing")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown resource field: missing");
    }

    @Test
    void validateAllAcceptsCompilablePolicies() {
        PolicyCondition nested = new PolicyCondition.And(List.of(
                new PolicyCondition.Exists(PolicyOperand.resource("status")),
                new PolicyCondition.Or(List.of(
                        compare(PolicyOperand.resource("org_unit_id"),
                                PolicyComparisonOperator.IN,
                                PolicyOperand.literal(List.of("D01"))),
                        new PolicyCondition.Not(compare(
                                PolicyOperand.environment("department"),
                                PolicyComparisonOperator.EQ,
                                PolicyOperand.literal("D01")))))));
        PolicyScopeCompiler compiler = compiler(List.of(
                allow("p1", nested),
                deny("p2", compare(PolicyOperand.resource("status"),
                        PolicyComparisonOperator.NE, PolicyOperand.literal("A")))));

        compiler.validateAll();
    }

    @Test
    void validateAllRejectsUnknownField() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.subject("department"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("missing"))))).validateAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown resource field: missing");
    }

    @Test
    void validateAllRejectsUndeclaredEnvironmentKey() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.environment("missing"), PolicyComparisonOperator.EQ,
                PolicyOperand.literal("D01"))))).validateAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("undeclared environment key: missing");
    }

    @Test
    void validateAllRejectsUnusableConditions() {
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.EQ,
                PolicyOperand.resource("name"))))).validateAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field-to-field comparison");
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.resource("status"), PolicyComparisonOperator.CONTAINS,
                PolicyOperand.literal("A"))))).validateAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operator CONTAINS");
        assertThatThrownBy(() -> compiler(List.of(allow("p1", compare(
                PolicyOperand.action(), PolicyComparisonOperator.EQ,
                PolicyOperand.literal("read"))))).validateAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action operand");
    }

    @Test
    void validateAllIgnoresNonReadAndUnmatchedPolicies() {
        IamPolicy write = new IamPolicy("p-write", "default", "p-write", IamPolicyEffect.ALLOW,
                0, Set.of("write"), Set.of("employee"),
                new PolicyCondition.Exists(PolicyOperand.resource("nope")), true, 0);
        IamPolicy otherResource = new IamPolicy("p-invoice", "default", "p-invoice",
                IamPolicyEffect.ALLOW, 0, Set.of("read"), Set.of("invoice"),
                new PolicyCondition.Exists(PolicyOperand.resource("nope")), true, 0);

        compiler(List.of(write, otherResource)).validateAll();
    }

    @Test
    void missingConstantBecomesNone() {
        assertThat(compiler(List.of(allow("p1", compare(PolicyOperand.resource("status"),
                PolicyComparisonOperator.EQ, PolicyOperand.subject("missing")))))
                .compile("employee", caller(), ScopeContext.empty()))
                .isEqualTo(ScopePredicate.none());
        assertThat(constantCompare(PolicyComparisonOperator.EQ, PolicyOperand.subject("missing"),
                PolicyOperand.literal("anything"))).isEqualTo(ScopePredicate.none());
        assertThat(constantCompare(PolicyComparisonOperator.NE, PolicyOperand.subject("missing"),
                PolicyOperand.literal("anything"))).isEqualTo(ScopePredicate.all());
    }

    private ScopePredicate constantEqualsSubject(Caller caller, String path, Object value) {
        return compiler(List.of(allow("p1", compare(PolicyOperand.subject(path),
                PolicyComparisonOperator.EQ, PolicyOperand.literal(value)))))
                .compile("employee", caller, ScopeContext.empty());
    }

    private boolean constantEqualsSubject(String path, Object value) {
        return compiler(List.of(allow("p1", compare(PolicyOperand.subject(path),
                PolicyComparisonOperator.EQ, PolicyOperand.literal(value)))))
                .compile("employee", caller(), ScopeContext.empty())
                .equals(ScopePredicate.all());
    }

    private ScopePredicate constantCompare(PolicyComparisonOperator operator,
                                           PolicyOperand left, PolicyOperand right) {
        return constantCompare(caller(), operator, left, right);
    }

    private ScopePredicate constantCompare(Caller caller, PolicyComparisonOperator operator,
                                           PolicyOperand left, PolicyOperand right) {
        return compiler(List.of(allow("p1", compare(left, operator, right))))
                .compile("employee", caller, ScopeContext.empty());
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
            return policies.stream()
                    .filter(policy -> policy.enabled() && policy.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public List<IamPolicy> findAll(String tenantId) {
            return policies.stream()
                    .filter(policy -> policy.tenantId().equals(tenantId))
                    .toList();
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
