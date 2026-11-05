package cn.byteforce.seedj.iam.authz;

import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.jdbc.JdbcIamBindingStore;
import cn.byteforce.seedj.iam.memory.InMemoryIamAttributeSource;
import cn.byteforce.seedj.iam.memory.InMemoryIamBindingStore;
import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 授权契约装配：存储 provider、属性源、SoD 规则与解析服务。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seedj.authz", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AuthzProperties.class)
public class IamAuthzConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IamBindingStore iamBindingStore(AuthzProperties authz, IamProperties iam,
                                           ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        String provider = authz.provider();
        if (provider == null || provider.isBlank()) {
            provider = template != null && iam.store() == IamProperties.Store.JDBC
                    ? "postgres" : "memory";
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
            default -> throw new IllegalStateException(
                    "unknown seedj.authz.provider: " + provider);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public IamAttributeSource iamAttributeSource() {
        return new InMemoryIamAttributeSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public SodRuleSet sodRuleSet() {
        return SodRuleSet.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public IamContractAuthorizationService iamContractAuthorizationService(
            IamBindingStore bindings, IamAuthorizationRepository roles,
            IamAttributeSource attributes,
            ObjectProvider<IamPermissionCondition> conditions, SodRuleSet ruleSet) {
        List<IamPermissionCondition> declared = conditions.orderedStream().toList();
        return new IamContractAuthorizationService(bindings, roles, attributes, declared,
                ruleSet);
    }
}
