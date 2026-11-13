package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/** {@code @EnableImServer} 的标记语义：只有显式启用才注册 {@link ImEnabledMarker}。 */
class ImEnumerationTest {

    @Test
    @DisplayName("带 @EnableImServer 的配置注册标记 bean")
    void annotationRegistersMarker() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(AnnotatedConfig.class);
            context.refresh();

            assertThat(context.getBean(ImEnabledMarker.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("普通配置不注册标记 bean")
    void plainConfigurationHasNoMarker() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            context.register(PlainConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(ImEnabledMarker.class)).isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableImServer
    static class AnnotatedConfig {}

    @Configuration(proxyBeanMethods = false)
    static class PlainConfig {}
}
