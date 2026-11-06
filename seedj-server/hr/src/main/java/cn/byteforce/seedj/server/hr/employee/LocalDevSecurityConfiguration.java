package cn.byteforce.seedj.server.hr.employee;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * 本地开发安全链：{@code seedj.biz.dev-auth.enabled=true} 时生效。
 *
 * <p>顺序在业务链（order 6）之前，permitAll + 本地过滤器；关闭该开关即整条链不生效，
 * 回落到需要真实令牌的业务链。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seedj.biz.dev-auth", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class LocalDevSecurityConfiguration {

    /**
     * 本地过滤链。
     *
     * @param http Spring Security 构建器
     * @return 过滤链
     * @throws Exception 构建失败
     */
    @Bean
    @Order(5)
    public SecurityFilterChain localDevSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .addFilterBefore(new LocalDevAuthenticationFilter(
                                LocalDevAuthenticationFilter.localAdmin()),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
