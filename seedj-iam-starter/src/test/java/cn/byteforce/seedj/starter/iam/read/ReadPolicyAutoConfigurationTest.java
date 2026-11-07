package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.iam.IamAuthorizationDecision;
import cn.byteforce.seedj.iam.IamPolicy;
import cn.byteforce.seedj.iam.PolicyComparisonOperator;
import cn.byteforce.seedj.iam.PolicyCondition;
import cn.byteforce.seedj.iam.PolicyOperand;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamPolicyRepository;
import cn.byteforce.seedj.security.AuthorizationDecision;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import cn.byteforce.seedj.module.ObjectModules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 读策略装配：编译器、行范围与字段策略解析器，以及启动期校验。 */
class ReadPolicyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReadPolicyAutoConfiguration.class));

    @Test
    @DisplayName("依赖齐备时装配编译器、行范围与字段策略解析器")
    void wiresAllBeans() {
        runner.withUserConfiguration(RegistryConfiguration.class)
                .withBean(IamPolicyRepository.class, InMemoryIamPolicyRepository::new)
                .withBean(IamPolicyDecisionPoint.class, ReadPolicyAutoConfigurationTest::denyAll)
                .withBean(AuthorizationDecisionPoint.class,
                        () -> request -> AuthorizationDecision.deny("no", "no", null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PolicyScopeCompiler.class);
                    assertThat(context).hasSingleBean(IamDataScopeResolver.class);
                    assertThat(context).hasSingleBean(IamFieldPolicyResolver.class);
                });
    }

    @Test
    @DisplayName("缺少 PDP 时不装配读策略 bean")
    void backsOffWithoutPdp() {
        runner.withUserConfiguration(RegistryConfiguration.class)
                .withBean(IamPolicyRepository.class, InMemoryIamPolicyRepository::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PolicyScopeCompiler.class);
                    assertThat(context).doesNotHaveBean(IamFieldPolicyResolver.class);
                });
    }

    @Test
    @DisplayName("自定义编译器优先于默认装配")
    void customCompilerWins() {
        runner.withUserConfiguration(CustomCompilerConfiguration.class)
                .withBean(IamPolicyRepository.class, InMemoryIamPolicyRepository::new)
                .withBean(IamPolicyDecisionPoint.class, ReadPolicyAutoConfigurationTest::denyAll)
                .withBean(AuthorizationDecisionPoint.class,
                        () -> request -> AuthorizationDecision.deny("no", "no", null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PolicyScopeCompiler.class))
                            .isSameAs(CustomCompilerConfiguration.COMPILER);
                });
    }

    @Test
    @DisplayName("启动期校验拒绝不可编译的启用策略")
    void startupValidationRejectsUncompilablePolicy() {
        InMemoryIamPolicyRepository policies = new InMemoryIamPolicyRepository();
        policies.save(new IamPolicy("p1", "default", "p1",
                cn.byteforce.seedj.iam.IamPolicyEffect.ALLOW, 0, Set.of("read"),
                Set.of("employee"), new PolicyCondition.Compare(
                        PolicyOperand.resource("missing"), PolicyComparisonOperator.EQ,
                        PolicyOperand.literal("x")), true, 0));

        runner.withUserConfiguration(RegistryConfiguration.class)
                .withBean(IamPolicyRepository.class, () -> policies)
                .withBean(IamPolicyDecisionPoint.class, ReadPolicyAutoConfigurationTest::denyAll)
                .withBean(AuthorizationDecisionPoint.class,
                        () -> request -> AuthorizationDecision.deny("no", "no", null))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("unknown resource field: missing");
                });
    }

    private static IamPolicyDecisionPoint denyAll() {
        return request -> IamAuthorizationDecision.deny("no matching allow policy");
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfiguration {

        @Bean
        ObjectModuleRegistry objectModuleRegistry() {
            ObjectModules modules = ObjectModules.create();
            modules.object("employee", "hr_employee")
                    .readPermission("hr.employee.read")
                    .field("org_unit_id", FieldType.TEXT);
            return modules.freeze();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCompilerConfiguration {

        private static final ObjectModuleRegistry REGISTRY = ObjectModules.create().freeze();

        private static final PolicyScopeCompiler COMPILER = new PolicyScopeCompiler(
                new InMemoryIamPolicyRepository(), REGISTRY, REGISTRY.scopeKeys());

        @Bean
        ObjectModuleRegistry objectModuleRegistry() {
            return REGISTRY;
        }

        @Bean
        PolicyScopeCompiler policyScopeCompiler() {
            return COMPILER;
        }
    }
}
