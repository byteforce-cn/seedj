package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.abac.CompositeAuthorizationDecisionPoint;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 授权契约装配：属性源、SoD 规则与解析服务。
 *
 * <p>{@link IamBindingStore} 与 {@link IamAttributeSource} 由适配器按 {@code seedj.authz.provider} （缺省跟随
 * {@code seedj.iam.store}）提供；starter 只消费这两个端口，不再持有任何具体实现。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "seedj.authz",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IamAuthzConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CallerProvider iamCallerProvider(IamProperties iam) {
        return new IamCallerProvider(iam.tenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public SodRuleSet sodRuleSet() {
        return SodRuleSet.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public IamContractAuthorizationService iamContractAuthorizationService(
            IamBindingStore bindings,
            IamAuthorizationRepository roles,
            IamAttributeSource attributes,
            ObjectProvider<IamPermissionCondition> conditions,
            SodRuleSet ruleSet) {
        List<IamPermissionCondition> declared = conditions.orderedStream().toList();
        return new IamContractAuthorizationService(bindings, roles, attributes, declared, ruleSet);
    }

    /**
     * 唯一授权决策点：命令、读路径与 /check 共用。
     *
     * @param contract 04 授权契约服务
     * @param policies ABAC 策略引擎（可能未启用）
     * @return 组合决策点
     */
    @Bean
    @ConditionalOnMissingBean
    public AuthorizationDecisionPoint authorizationDecisionPoint(
            IamContractAuthorizationService contract,
            @org.springframework.beans.factory.annotation.Qualifier("iamPolicyDecisionPoint")
                    ObjectProvider<IamPolicyDecisionPoint> policies) {
        return new CompositeAuthorizationDecisionPoint(contract::decide, policies.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public IamAuthorizationController iamAuthorizationController(
            IamContractAuthorizationService service,
            AuthorizationDecisionPoint decisions,
            ObjectProvider<CallerProvider> callers) {
        return new IamAuthorizationController(service, decisions, callers);
    }
}
