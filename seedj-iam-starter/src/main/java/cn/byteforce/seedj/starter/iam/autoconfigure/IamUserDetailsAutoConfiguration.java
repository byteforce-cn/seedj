package cn.byteforce.seedj.starter.iam.autoconfigure;

import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.starter.iam.identity.IamUserDetailsService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Registered as a separate auto-configuration so an application-provided UserDetailsService (common
 * in tests and existing apps) wins cleanly. Defining this bean inside the imported security
 * configuration made the condition run too early and produced two UserDetailsService beans.
 */
@AutoConfiguration(after = IamAutoConfiguration.class)
@ConditionalOnBean(IamEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.iam",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IamUserDetailsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    public UserDetailsService iamUserDetailsService(
            IamUserRepository users,
            IamAuthorizationService authorization,
            IamProperties properties) {
        return new IamUserDetailsService(users, authorization, properties.tenantId());
    }
}
