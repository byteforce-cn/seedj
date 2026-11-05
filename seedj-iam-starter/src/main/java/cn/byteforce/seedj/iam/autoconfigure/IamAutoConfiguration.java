package cn.byteforce.seedj.iam.autoconfigure;

import cn.byteforce.seedj.iam.config.IamProperties;
import cn.byteforce.seedj.iam.config.IamServerAutoConfiguration;
import cn.byteforce.seedj.iam.security.PkiIamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.pki.PkiProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Single IAM auto-configuration entry point. It activates only when
 * {@code @EnableIamServer} registered the marker bean, and every sub-capability
 * can be switched off independently.
 */
@AutoConfiguration
@ConditionalOnBean(IamEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.iam", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IamProperties.class)
@Import({IamStoreConfiguration.class, IamJdbcStoreConfiguration.class,
        IamServerAutoConfiguration.class, IamNativeAuthConfiguration.class,
        IamRbacConfiguration.class, IamScimConfiguration.class, IamAbacConfiguration.class,
        IamWebConfiguration.class})
public class IamAutoConfiguration {

    /**
     * Runs before any repository bean is instantiated, so a missing DataSource is
     * reported as an IAM configuration error, not as a generic missing-bean error
     * from whichever repository happens to be created first.
     */
    @org.springframework.context.annotation.Bean
    @ConditionalOnProperty(prefix = "seedj.iam", name = "store", havingValue = "jdbc")
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor
            iamJdbcStorePrecondition() {
        return beanFactory -> {
            if (beanFactory.getBeanNamesForType(javax.sql.DataSource.class).length == 0) {
                throw new IllegalStateException("seedj.iam.store=jdbc requires a DataSource; "
                        + "configure spring.datasource.* or switch to seedj.iam.store=memory");
            }
        };
    }

    @org.springframework.context.annotation.Bean
    IamInfrastructureInitializer iamInfrastructureInitializer(
            ObjectProvider<InfrastructureCapability> capabilities) {
        return new IamInfrastructureInitializer(capabilities);
    }

    @Bean
    @ConditionalOnMissingBean(IamSigningKeyStore.class)
    @ConditionalOnProperty(prefix = "seedj.iam.jwk", name = "provider", havingValue = "pki")
    IamSigningKeyStore pkiIamSigningKeyStore(ObjectProvider<PkiProvider> pki) {
        PkiProvider provider = pki.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("seedj.iam.jwk.provider=pki requires a PkiProvider; "
                    + "enable seedj.pki and provide a CA, or use the default JDBC/memory JWK store");
        }
        return new PkiIamSigningKeyStore(provider, "iam-jwt-signer");
    }
}
