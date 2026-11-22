package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.iam.spi.IamAuthorizationRepository;
import cn.byteforce.seedj.iam.spi.IamGroupRepository;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.iam.engine.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.scim.ScimController;
import cn.byteforce.seedj.starter.iam.scim.ScimExceptionHandler;
import cn.byteforce.seedj.iam.engine.scim.ScimGroupService;
import cn.byteforce.seedj.iam.engine.scim.ScimSupport;
import cn.byteforce.seedj.iam.engine.scim.ScimUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** SCIM 2.0 provisioning endpoints; independently switchable. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "seedj.iam.scim",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({ScimController.class, ScimExceptionHandler.class})
public class IamScimConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ScimUserService scimUserService(
            IamUserRepository users,
            IamGroupRepository groups,
            IamPasswordHasher hasher,
            IamIdentityService identities,
            IamProperties properties,
            IdGenerator<String> idGenerator) {
        return new ScimUserService(
                users, groups, hasher, identities, scimSettings(properties), idGenerator);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScimGroupService scimGroupService(
            IamGroupRepository groups,
            IamUserRepository users,
            IamProperties properties,
            IamAuthorizationRepository authorization,
            IdGenerator<String> idGenerator) {
        return new ScimGroupService(
                groups, users, scimSettings(properties), authorization, idGenerator);
    }

    private static ScimSupport.Settings scimSettings(IamProperties properties) {
        return new ScimSupport.Settings(properties.tenantId(), properties.scim().basePath());
    }
}
