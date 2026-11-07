package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.ScopeContext;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamPolicyRepository;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 读路径行范围贡献者：只接管已声明对象，并复用策略编译器。 */
class IamDataScopeResolverTest {

    private static ObjectModuleRegistry registry() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("org_unit_id", FieldType.TEXT);
        return modules.freeze();
    }

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", Set.of(), Set.of(), Set.of(),
                Set.of("pwd"), "urn:seedj:acr:pwd", Map.of());
    }

    private static ScopePredicate compileWithAllow() {
        InMemoryIamPolicyRepository policies = new InMemoryIamPolicyRepository();
        policies.save(IamPolicy.allow("p1", "default", "p1", Set.of("read"),
                Set.of("employee"), new PolicyCondition.Compare(PolicyOperand.resource("org_unit_id"),
                        PolicyComparisonOperator.EQ, PolicyOperand.literal("D01"))));
        ObjectModuleRegistry modules = registry();
        return new IamDataScopeResolver(
                new PolicyScopeCompiler(policies, modules, modules.scopeKeys()), modules)
                .resolve("employee", caller(), ScopeContext.empty());
    }

    @Test
    @DisplayName("supports 只对已声明对象类型返回 true")
    void supportsOnlyDeclaredTypes() {
        ObjectModuleRegistry modules = registry();
        IamDataScopeResolver resolver = new IamDataScopeResolver(
                new PolicyScopeCompiler(new InMemoryIamPolicyRepository(), modules,
                        modules.scopeKeys()), modules);

        assertThat(resolver.supports("employee")).isTrue();
        assertThat(resolver.supports("invoice")).isFalse();
    }

    @Test
    @DisplayName("resolve 返回策略编译出的行范围")
    void resolveDelegatesToCompiler() {
        assertThat(compileWithAllow()).isEqualTo(ScopePredicate.eq("org_unit_id", "D01"));
    }
}
