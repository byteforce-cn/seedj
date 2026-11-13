package cn.byteforce.seedj.starter.im;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 只产 {@link ImEnabledMarker}；装配由 {@code AutoConfiguration.imports} 发现，但只有本标记存在时才激活。 */
@Configuration(proxyBeanMethods = false)
class ImEnablementConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ImEnabledMarker imEnabledMarker() {
        return new ImEnabledMarker();
    }
}
