package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** {@code seedj.im.*} 的默认值与显式绑定。 */
class ImPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("空属性时所有默认值命中")
    void emptyPropertiesYieldDefaults() {
        runner.run(
                context -> {
                    ImProperties properties = context.getBean(ImProperties.class);

                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.push().provider()).isEqualTo("local");
                    assertThat(properties.tcp().isEnabled()).isTrue();
                    assertThat(properties.tcp().port()).isEqualTo(9090);
                    assertThat(properties.ws().isEnabled()).isTrue();
                    assertThat(properties.ws().port()).isEqualTo(9091);
                    assertThat(properties.ws().path()).isEqualTo("/ws");
                    assertThat(properties.heartbeatTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(properties.maxFrameLength()).isEqualTo(1_048_576);
                    assertThat(properties.maxSessionsPerUser()).isEqualTo(1000);
                });
    }

    @Test
    @DisplayName("tcp.port=0 原样保留，供测试绑定随机端口")
    void bindsZeroTcpPort() {
        runner.withPropertyValues("seedj.im.tcp.port=0")
                .run(
                        context ->
                                assertThat(context.getBean(ImProperties.class).tcp().port())
                                        .isZero());
    }

    @Test
    @DisplayName("push.provider 可覆盖")
    void bindsPushProvider() {
        runner.withPropertyValues("seedj.im.push.provider=redis")
                .run(
                        context ->
                                assertThat(
                                                context.getBean(ImProperties.class)
                                                        .push()
                                                        .provider())
                                        .isEqualTo("redis"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImProperties.class)
    static class PropertiesConfig {}
}
