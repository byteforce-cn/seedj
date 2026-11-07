package cn.byteforce.seedj.starter.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by {@code @EnableBizServer}; deliberately contains no component scan
 * and no {@code @SpringBootApplication} semantics.
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class BizEnablementConfiguration {

    @Bean
    @ConditionalOnMissingBean
    BizEnabledMarker bizEnabledMarker() {
        return new BizEnabledMarker();
    }
}
