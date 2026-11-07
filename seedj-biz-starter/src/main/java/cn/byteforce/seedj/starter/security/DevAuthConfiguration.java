package cn.byteforce.seedj.starter.security;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地开发认证链：starter 拥有，宿主只开开关。
 *
 * <p>在业务链（order 6）之前提供固定 {@link Caller}，让 quickstart 不依赖 IdP；
 * 关闭 {@code seedj.biz.dev-auth.enabled} 即整条链不生效。生产 profile 由
 * {@code DegradedModeGuard} 在启动期拒绝。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seedj.biz.dev-auth", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(DevAuthProperties.class)
class DevAuthConfiguration {

    /**
     * 本地过滤链。
     *
     * @param http          Spring Security 构建器
     * @param properties    本地调用方配置
     * @param entryPoint    401 信封
     * @param deniedHandler 403 信封
     * @return 过滤链
     * @throws Exception 构建失败
     */
    @Bean
    @Order(5)
    SecurityFilterChain devAuthSecurityFilterChain(
            HttpSecurity http, DevAuthProperties properties,
            JsonAuthenticationEntryPoint entryPoint, JsonAccessDeniedHandler deniedHandler)
            throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(new DevAuthenticationFilter(caller(properties)),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    static Caller caller(DevAuthProperties properties) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        properties.attributes().forEach(attributes::put);
        return new Caller(properties.callerId(), properties.name(), Caller.DEFAULT_TENANT,
                Set.of(), properties.permissions(), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, attributes);
    }

    private static final class DevAuthenticationFilter extends OncePerRequestFilter {

        private final Caller caller;

        private DevAuthenticationFilter(Caller caller) {
            this.caller = caller;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                List<GrantedAuthority> authorities = caller.permissions().stream()
                        .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(
                                AccessTokenRules.PERMISSION_PREFIX + permission))
                        .toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(caller, null, authorities));
            }
            chain.doFilter(request, response);
        }
    }
}
