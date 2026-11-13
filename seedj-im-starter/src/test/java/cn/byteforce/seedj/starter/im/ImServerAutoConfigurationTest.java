package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** 自动配置：JwtDecoder 就绪时启动 TCP，WS 开关生效。 */
class ImServerAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ImServerAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class)
                    .withBean(JwtClaimsMapper.class)
                    .withBean(
                            JwtDecoder.class,
                            () ->
                                    token -> {
                                        throw new JwtException("unused");
                                    });

    @Test
    @DisplayName("有 JwtDecoder 时装配 TCP，WS 关闭时不装配")
    void startsTcpWhenDecoderPresent() {
        runner.withPropertyValues("seedj.im.tcp.port=0", "seedj.im.ws.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ImTcpServer.class);
                            assertThat(context).doesNotHaveBean(ImWsServer.class);
                        });
    }

    @Test
    @DisplayName("没有 JwtDecoder 时不监听端口")
    void doesNotStartWithoutDecoder() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ImServerAutoConfiguration.class))
                .withBean(ImEnabledMarker.class)
                .withPropertyValues("seedj.im.tcp.port=0", "seedj.im.ws.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ImTcpServer.class));
    }
}
