package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import io.netty.bootstrap.ServerBootstrap;
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
        afterName = "cn.byteforce.seedj.starter.security.BizSecurityAutoConfiguration")
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnClass(ServerBootstrap.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ImProperties.class)
public class ImServerAutoConfiguration {

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

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({JwtDecoder.class, JwtClaimsMapper.class})
    ImJwtAuthenticator imJwtAuthenticator(JwtDecoder decoder, JwtClaimsMapper mapper) {
        return new ImJwtAuthenticator(decoder, mapper);
    }

    /** 传输层只在握手器就绪时启动；没有 JwtDecoder 时不监听端口。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(ImJwtAuthenticator.class)
    static class TransportConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ImHandshakeHandler imHandshakeHandler(
                ImCodec codec, ImJwtAuthenticator authenticator, ChannelRegistry registry) {
            return new ImHandshakeHandler(codec, authenticator, registry);
        }

        @Bean
        @ConditionalOnMissingBean
        ImDispatchHandler imDispatchHandler(ImCodec codec) {
            return new ImDispatchHandler(codec);
        }

        @Bean
        @ConditionalOnMissingBean
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
