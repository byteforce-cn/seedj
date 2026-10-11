package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.web.core.RequestContextContributor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * JWT 资源服务自动配置（seedj ADR-0002 错误契约的安全侧落地）：
 *
 * <ul>
 *   <li>默认安全链：无状态会话、CSRF 关闭、{@code permitPaths} 放行、其余请求需认证；</li>
 *   <li>401 / 403 输出统一 {@code ApiResponse} 错误体（含 traceId）；</li>
 *   <li>JWT 权限映射：缺省 {@code scope/scp} 之外，消费配置的自定义权限 claim
 *       （{@link ResourceServerProperties.Claims#getAuthorities()}，默认 {@code authorities}）；</li>
 *   <li>JWT 身份（用户 / 租户 / 用户名）写入 {@code RequestContext} 供业务与数据层使用。</li>
 * </ul>
 *
 * <p><b>前置条件</b>：容器中必须存在 {@code JwtDecoder} Bean ——
 * 由应用通过 Spring Boot 原生属性 {@code spring.security.oauth2.resourceserver.jwt.*}
 * 配置（推荐），或自行注册 {@code JwtDecoder}。缺失时启动即失败（fail-fast）。</p>
 *
 * <p>自定义安全策略：应用注册自己的 {@code SecurityFilterChain}（标注更小 {@code @Order}）
 * 即可优先于本默认链。可通过 {@code byteforce.security.resource.enabled=false} 整体关闭。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Jwt.class, BearerTokenAuthenticationToken.class})
@ConditionalOnProperty(prefix = "byteforce.security.resource", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ResourceServerProperties.class)
public class ResourceServerAutoConfiguration {

    /**
     * 缺省 JWT 认证转换器：scope/scp 标准映射 + 自定义权限 claim（{@code claims.authorities}）合并。
     *
     * @param properties 资源服务属性
     * @return JWT 认证转换器
     */
    @Bean
    @ConditionalOnMissingBean(name = "seedjJwtAuthenticationConverter")
    public Converter<Jwt, AbstractAuthenticationToken> seedjJwtAuthenticationConverter(
            ResourceServerProperties properties) {
        return new SeedjJwtAuthenticationConverter(properties.getClaims());
    }

    /**
     * 默认安全链（资源服务基线）。
     *
     * @param http                       HttpSecurity
     * @param properties                 资源服务属性
     * @param jsonMapper                 JSON 序列化器
     * @param jwtAuthenticationConverter JWT 认证转换器（自定义权限 claim 消费）
     * @return 安全过滤链
     * @throws Exception 构建失败
     */
    @Bean
    @ConditionalOnMissingBean(name = "seedjResourceServerSecurityFilterChain")
    public SecurityFilterChain seedjResourceServerSecurityFilterChain(
            HttpSecurity http, ResourceServerProperties properties, JsonMapper jsonMapper,
            @Qualifier("seedjJwtAuthenticationConverter") Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        String[] permitPaths = properties.getPermitPaths().toArray(String[]::new);
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(permitPaths).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(new ApiResponseAuthenticationEntryPoint(jsonMapper))
                        .accessDeniedHandler(new ApiResponseAccessDeniedHandler(jsonMapper))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * JWT 身份贡献器：把安全上下文中的 JWT claim 写入 {@code RequestContext}
     * （由 starter-web 的身份过滤器调度）。
     *
     * @param properties 资源服务属性
     * @return 身份贡献器
     */
    @Bean
    @ConditionalOnMissingBean(name = "seedjSecurityContextRequestContextContributor")
    public RequestContextContributor seedjSecurityContextRequestContextContributor(
            ResourceServerProperties properties) {
        return new SecurityContextRequestContextContributor(properties.getClaims());
    }

    /**
     * 安全异常 MVC 侧处理器（403/401 统一错误体）。
     *
     * @return 安全异常处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public SeedjSecurityExceptionHandler seedjSecurityExceptionHandler() {
        return new SeedjSecurityExceptionHandler();
    }
}

