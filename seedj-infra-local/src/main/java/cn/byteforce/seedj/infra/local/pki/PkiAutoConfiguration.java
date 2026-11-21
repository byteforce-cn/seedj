package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.provider.pki.PkiProvider;
import cn.byteforce.seedj.transit.TransitProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(name = "org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder")
@ConditionalOnProperty(
        prefix = "seedj.pki",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnProperty(
        prefix = "seedj.pki",
        name = "provider",
        havingValue = "local",
        matchIfMissing = true)
@EnableConfigurationProperties(PkiProperties.class)
public class PkiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PkiProvider pkiProvider(
            PkiProperties props, ObjectProvider<TransitProvider> transitProvider) {
        TransitProvider transit = transitProvider.getIfAvailable();
        return new BcPkiProvider(
                props.getCaResource(),
                props.getCaPassword().toCharArray(),
                props.getCaAlias(),
                props.getRevocationStore(),
                transit == null ? null : transit.signer());
    }
}
