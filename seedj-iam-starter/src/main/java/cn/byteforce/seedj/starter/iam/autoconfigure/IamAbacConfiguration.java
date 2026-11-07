package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.iam.policy.DefaultIamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyDecisionPoint;
import cn.byteforce.seedj.iam.spi.IamPolicyRepository;
import cn.byteforce.seedj.iam.spi.IamResourceAttributeResolver;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.abac.CachingIamPolicyDecisionPoint;
import cn.byteforce.seedj.starter.iam.abac.IamAuthorizationFacade;
import cn.byteforce.seedj.starter.iam.abac.IamPolicyAdminController;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** ABAC PDP, resource resolver seam, cached decisions, and management API. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "seedj.iam.abac",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import(IamPolicyAdminController.class)
public class IamAbacConfiguration {

    @Bean(name = "defaultIamPolicyDecisionPoint")
    @ConditionalOnMissingBean(name = "defaultIamPolicyDecisionPoint")
    public IamPolicyDecisionPoint defaultIamPolicyDecisionPoint(
            IamPolicyRepository policies,
            ObjectProvider<IamResourceAttributeResolver> resolvers,
            SecurityAuditSink audit,
            IdGenerator<String> idGenerator) {
        List<IamResourceAttributeResolver> ordered = resolvers.orderedStream().toList();
        return new DefaultIamPolicyDecisionPoint(policies, ordered, audit, idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean(name = "iamPolicyDecisionPoint")
    public IamPolicyDecisionPoint iamPolicyDecisionPoint(
            IamPolicyRepository policies,
            @Qualifier("defaultIamPolicyDecisionPoint") IamPolicyDecisionPoint delegate,
            IamProperties properties) {
        return new CachingIamPolicyDecisionPoint(delegate, policies, properties.abac().cacheTtl());
    }

    @Bean(name = "iam")
    @ConditionalOnMissingBean(name = "iam")
    public IamAuthorizationFacade iamAuthorizationFacade(
            @Qualifier("iamPolicyDecisionPoint") IamPolicyDecisionPoint policyDecisionPoint,
            IamUserRepository users,
            IamAuthorizationService authorization,
            IamProperties properties) {
        return new IamAuthorizationFacade(policyDecisionPoint, users, authorization, properties);
    }
}
