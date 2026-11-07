package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.TransitProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Transit 的默认装配：一个 {@link TransitProvider} bean，默认磁盘共享状态。
 *
 * <p>业务方只需注入 {@code TransitProvider}；要换成远程 KMS，提供自己的 {@code TransitProvider} bean 即可（{@link
 * ConditionalOnMissingBean} 会退让）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.bouncycastle.jce.provider.BouncyCastleProvider")
@ConditionalOnProperty(
        prefix = "seedj.transit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(TransitProperties.class)
public class TransitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransitProvider transitProvider(TransitProperties properties) {
        return properties.isPersist()
                ? BcTransitProvider.fileBacked(properties.getStateFile())
                : new BcTransitProvider();
    }
}
