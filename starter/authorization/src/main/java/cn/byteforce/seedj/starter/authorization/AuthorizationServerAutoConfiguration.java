package cn.byteforce.seedj.starter.authorization;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * 授权服务自动配置：seedj 组织级缺省（issuer、访问令牌身份 claim）。
 *
 * <p><b>职责边界</b>（v1）：协议面完全沿用 Spring Boot 原生 OAuth2 授权服务器自动配置
 * （依赖 {@code spring-boot-starter-security-oauth2-authorization-server}），
 * 客户端注册使用 Boot 原生属性 {@code spring.security.oauth2.authorizationserver.client.*}；
 * 本组件只补充：</p>
 * <ul>
 *   <li>{@code byteforce.security.authorization.issuer} 非空时注册缺省
 *       {@link AuthorizationServerSettings}（应用可覆盖）；</li>
 *   <li>注册 {@link SeedjJwtClaimsCustomizer}：把 {@code CustomUserDetails} 主体的
 *       用户 / 租户注入访问令牌，与 starter-resource 的身份解析闭环。</li>
 * </ul>
 *
 * <p>应用需提供 {@code JWKSource}（签名密钥）与 {@code UserDetailsService}（用户体系；
 * 建议返回 {@code CustomUserDetails} 以启用身份 claim 注入）。
 * 整体开关：{@code byteforce.security.authorization.enabled=false}。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({RegisteredClientRepository.class, JwtEncodingContext.class})
@ConditionalOnProperty(prefix = "byteforce.security.authorization", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthorizationServerProperties.class)
public class AuthorizationServerAutoConfiguration {

    /**
     * 缺省 {@link AuthorizationServerSettings}（仅当配置了 {@code issuer} 时注册）。
     *
     * @param properties 授权服务属性
     * @return 授权服务器设置
     */
    @Bean
    @ConditionalOnMissingBean(AuthorizationServerSettings.class)
    @ConditionalOnProperty(prefix = "byteforce.security.authorization", name = "issuer")
    public AuthorizationServerSettings seedjAuthorizationServerSettings(AuthorizationServerProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .build();
    }

    /**
     * 访问令牌身份 claim 定制器（主体为 {@code CustomUserDetails} 时生效）。
     *
     * @param properties 授权服务属性
     * @return 令牌定制器
     */
    @Bean
    @ConditionalOnMissingBean(OAuth2TokenCustomizer.class)
    public SeedjJwtClaimsCustomizer seedjJwtClaimsCustomizer(AuthorizationServerProperties properties) {
        return new SeedjJwtClaimsCustomizer(properties);
    }
}
