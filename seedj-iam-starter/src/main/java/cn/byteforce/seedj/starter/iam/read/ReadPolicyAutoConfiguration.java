package cn.byteforce.seedj.starter.iam.read;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import org.springframework.beans.factory.ObjectProvider;
import cn.byteforce.seedj.module.ObjectModuleRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * IAM 策略接入读路径：行范围编译器与字段策略解析器。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(afterName = {
        "cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration",
        "cn.byteforce.seedj.starter.read.ObjectModuleAutoConfiguration"})
@ConditionalOnBean({IamPolicyRepository.class, IamPolicyDecisionPoint.class,
        ObjectModuleRegistry.class})
public class ReadPolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PolicyScopeCompiler policyScopeCompiler(IamPolicyRepository policies,
                                                   ObjectModuleRegistry modules) {
        return new PolicyScopeCompiler(policies, modules, modules.scopeKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    public IamDataScopeResolver iamDataScopeResolver(PolicyScopeCompiler compiler,
                                                     ObjectModuleRegistry modules) {
        return new IamDataScopeResolver(compiler, modules);
    }

    @Bean
    @ConditionalOnMissingBean
    public IamFieldPolicyResolver iamFieldPolicyResolver(AuthorizationDecisionPoint decisions,
                                                         ObjectProvider<Clock> clock,
                                                         ObjectModuleRegistry modules) {
        return new IamFieldPolicyResolver(decisions, clock.getIfAvailable(Clocks::system),
                modules);
    }

    @Bean
    public InitializingBean readPolicyValidation(PolicyScopeCompiler compiler) {
        return compiler::validateAll;
    }
}
