package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import io.netty.bootstrap.ServerBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * IM 长连接自动配置：只在 {@code @EnableImServer} 且 {@code seedj.im.enabled=true} 时激活。
 *
 * <p>排在 biz 安全配置之后，确保 {@code JwtDecoder} 与 {@code Caller} 绑定已经就绪。
 */
@AutoConfiguration(
        afterName = {
            "cn.byteforce.seedj.starter.security.BizSecurityAutoConfiguration",
            "org.springframework.boot.security.oauth2.server.resource.autoconfigure"
                    + ".OAuth2ResourceServerAutoConfiguration"
        })
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnClass(ServerBootstrap.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ImProperties.class)
public class ImServerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ImServerAutoConfiguration.class);

    /** 没有 JwtDecoder 时传输层不会启动；这里留下一条可诊断的 WARN，避免端口静默不监听。 */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    MissingJwtDecoderWarning imMissingJwtDecoderWarning() {
        log.warn(
                "seedj.im is enabled but no JwtDecoder bean is available; TCP/WebSocket"
                        + " transports are disabled. Configure"
                        + " spring.security.oauth2.resourceserver.jwt.issuer-uri or provide a"
                        + " JwtDecoder bean.");
        return new MissingJwtDecoderWarning();
    }

    /** 诊断标记，避免静默无端口。 */
    static final class MissingJwtDecoderWarning {}

    @Bean
    @ConditionalOnMissingBean
    ImCodec imCodec() {
        return new ImCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    ChannelRegistry imChannelRegistry() {
        return new ChannelRegistry();
    }

    /** 传输层只在握手器就绪时启动；没有 JwtDecoder 时不监听端口。 */
    @Configuration(proxyBeanMethods = false)
    static class TransportConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean({JwtDecoder.class, JwtClaimsMapper.class})
        ImJwtAuthenticator imJwtAuthenticator(JwtDecoder decoder, JwtClaimsMapper mapper) {
            return new ImJwtAuthenticator(decoder, mapper);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        ImHandshakeHandler imHandshakeHandler(
                ImCodec codec, ImJwtAuthenticator authenticator, ChannelRegistry registry) {
            return new ImHandshakeHandler(codec, authenticator, registry);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        ImDispatchHandler imDispatchHandler(ImCodec codec) {
            return new ImDispatchHandler(codec);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        @ConditionalOnProperty(
                prefix = "seedj.im.tcp",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        ImChannelInitializer imChannelInitializer(
                ImProperties properties,
                ImHandshakeHandler handshakeHandler,
                ImDispatchHandler dispatchHandler) {
            return new ImChannelInitializer(properties, handshakeHandler, dispatchHandler);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        @ConditionalOnProperty(
                prefix = "seedj.im.tcp",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        ImTcpServer imTcpServer(ImProperties properties, ImChannelInitializer initializer) {
            return new ImTcpServer(properties, initializer);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        @ConditionalOnProperty(
                prefix = "seedj.im.ws",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        ImWsChannelInitializer imWsChannelInitializer(
                ImProperties properties,
                ImHandshakeHandler handshakeHandler,
                ImDispatchHandler dispatchHandler) {
            return new ImWsChannelInitializer(properties, handshakeHandler, dispatchHandler);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(ImJwtAuthenticator.class)
        @ConditionalOnProperty(
                prefix = "seedj.im.ws",
                name = "enabled",
                havingValue = "true",
                matchIfMissing = true)
        ImWsServer imWsServer(ImProperties properties, ImWsChannelInitializer initializer) {
            return new ImWsServer(properties, initializer);
        }
    }
}
