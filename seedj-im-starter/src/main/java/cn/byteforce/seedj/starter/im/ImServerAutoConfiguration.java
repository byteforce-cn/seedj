package cn.byteforce.seedj.starter.im;

import io.netty.bootstrap.ServerBootstrap;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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
public class ImServerAutoConfiguration {}
