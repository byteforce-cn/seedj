package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.pki.PkiProvider;
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
@EnableConfigurationProperties(PkiProperties.class)
public class PkiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PkiProvider pkiProvider(PkiProperties props) {
        return new BcPkiProvider(
                props.getCaResource(),
                props.getCaPassword().toCharArray(),
                props.getCaAlias(),
                props.getRevocationStore());
    }
}
