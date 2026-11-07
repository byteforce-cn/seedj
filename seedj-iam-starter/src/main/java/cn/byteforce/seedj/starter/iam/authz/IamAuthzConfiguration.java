package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.iam.authz.IamAttributeSource;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.IamPermissionCondition;
import cn.byteforce.seedj.iam.authz.SodRuleSet;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.infra.jdbc.iam.JdbcIamBindingStore;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAttributeSource;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamBindingStore;
import cn.byteforce.seedj.security.AuthorizationDecisionPoint;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.abac.CompositeAuthorizationDecisionPoint;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 授权契约装配：存储 provider、属性源、SoD 规则与解析服务。
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
@EnableConfigurationProperties(AuthzProperties.class)
public class IamAuthzConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IamBindingStore iamBindingStore(
            AuthzProperties authz, IamProperties iam, ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        String provider = authz.provider();
        if (provider == null || provider.isBlank()) {
            provider =
                    template != null && iam.store() == IamProperties.Store.JDBC
                            ? "postgres"
                            : "memory";
        }
        return switch (provider) {
            case "memory" -> new InMemoryIamBindingStore();
            case "postgres" -> {
                if (template == null) {
                    throw new IllegalStateException(
                            "seedj.authz.provider=postgres requires a DataSource/JdbcTemplate");
                }
                yield new JdbcIamBindingStore(template);
            }
            default -> throw new IllegalStateException("unknown seedj.authz.provider: " + provider);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public IamAttributeSource iamAttributeSource() {
        return new InMemoryIamAttributeSource();
    }

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
    @Primary
    @ConditionalOnMissingBean(
            value = AuthorizationDecisionPoint.class,
            ignored = IamContractAuthorizationService.class)
    public AuthorizationDecisionPoint authorizationDecisionPoint(
            IamContractAuthorizationService contract,
            @org.springframework.beans.factory.annotation.Qualifier("iamPolicyDecisionPoint")
                    ObjectProvider<IamPolicyDecisionPoint> policies) {
        return new CompositeAuthorizationDecisionPoint(contract, policies.getIfAvailable());
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
