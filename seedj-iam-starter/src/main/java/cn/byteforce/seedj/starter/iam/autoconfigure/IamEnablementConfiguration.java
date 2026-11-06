package cn.byteforce.seedj.starter.iam.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by {@code @EnableIamServer}; deliberately contains no component scan
 * and no {@code @SpringBootApplication} semantics.
 */
@Configuration(proxyBeanMethods = false)
public class IamEnablementConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IamEnabledMarker iamEnabledMarker() {
        return new IamEnabledMarker();
    }
}
