package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.biz.autoconfigure.BizEnabledMarker;
import cn.byteforce.seedj.biz.web.ContractErrorHandler;
import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 业务服务资源服务器装配。
 *
 * <p>与 IAM starter 的分工：这里只<b>消费</b>令牌，不签发、不存储用户；因此
 * {@code seedj-biz-starter} 不依赖 {@code seedj-iam-starter}。令牌规则由
 * {@link AccessTokenRules}（core）提供，两侧共用同一份实现。</p>
 *
 * <p>过滤器链顺序见 {@link #BIZ_FILTER_CHAIN_ORDER}：IAM 的浏览器登录链在
 * {@code LOWEST_PRECEDENCE}，所以业务链先命中；单独使用 biz 时它就是唯一一条链。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnBean(BizEnabledMarker.class)
@ConditionalOnProperty(prefix = "seedj.biz", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(BizSecurityProperties.class)
@Import(DevAuthConfiguration.class)
public class BizSecurityAutoConfiguration {

    /**
     * 业务资源服务器链的顺序：1 SAS / 2 SCIM / 3 IAM 管理 / 4 actuator /
     * 6 biz / LAST IAM 浏览器登录（见 {@code IamServerAutoConfiguration}）。
     */
    public static final int BIZ_FILTER_CHAIN_ORDER = 6;

    /**
     * 契约错误信封：所有业务/基础设施错误都走同一映射，避免各 controller 自拼响应。
     *
     * @return 错误处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public ContractErrorHandler contractErrorHandler() {
        return new ContractErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public CallerPropagation callerPropagation() {
        return new ThreadLocalCallerPropagation();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtClaimsMapper jwtClaimsMapper() {
        return new JwtClaimsMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public CallerProvider callerProvider(CallerPropagation propagation, JwtClaimsMapper mapper,
                                         BizSecurityProperties properties) {
        return new JwtCallerProvider(propagation, mapper, properties.tenantId());
    }

    /**
     * 把当前调用方与请求事实带进 {@code @Async} 线程；Boot 自动配置的执行器会应用该装饰器。
     */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    public TaskDecorator requestContextDecorator(CallerPropagation propagation) {
        return new RequestContextDecorator(propagation);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @EnableWebSecurity
    @EnableMethodSecurity
    public static class WebSecurityConfiguration {

        @Bean
        @Order(BIZ_FILTER_CHAIN_ORDER)
        @ConditionalOnMissingBean(name = "bizSecurityFilterChain")
        @ConditionalOnProperty(prefix = "seedj.biz.dev-auth", name = "enabled",
                havingValue = "false", matchIfMissing = true)
        public SecurityFilterChain bizSecurityFilterChain(
                HttpSecurity http,
                BizSecurityProperties properties,
                JwtDecoder jwtDecoder,
                JwtAuthenticationConverter jwtAuthenticationConverter,
                JsonAuthenticationEntryPoint authenticationEntryPoint,
                JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
            http
                    .sessionManagement(session -> session
                            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(AbstractHttpConfigurer::disable)
                    .cors(Customizer.withDefaults())
                    .authorizeHttpRequests(authorize -> authorize
                            .requestMatchers(properties.publicPaths().toArray(String[]::new))
                            .permitAll()
                            .anyRequest().authenticated())
                    .exceptionHandling(handling -> handling
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                            .decoder(jwtDecoder)
                            .jwtAuthenticationConverter(jwtAuthenticationConverter)));
            return http.build();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "seedj.biz.dev-auth", name = "enabled",
                havingValue = "false", matchIfMissing = true)
        public JwtDecoder jwtDecoder(BizSecurityProperties properties,
                                     ObjectProvider<OAuth2ResourceServerProperties> bootProperties) {
            String issuer = resolveIssuer(properties, bootProperties.getIfAvailable());
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();

            List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
            // RFC 9068：访问令牌 typ=at+jwt、issuer、时间、非空 aud。
            // 判定常量与 IamJwtValidators 共用 AccessTokenRules，避免两侧漂移。
            validators.add(new JwtTypeValidator(AccessTokenRules.ACCESS_TOKEN_TYPE));
            validators.add(new JwtIssuerValidator(issuer));
            validators.add(new JwtTimestampValidator());
            validators.add(new JwtClaimValidator<>("aud", AccessTokenRules::hasAudience));
            if (properties.hasAudience()) {
                String audience = properties.audience();
                validators.add(new JwtClaimValidator<>("aud",
                        value -> value instanceof Collection<?> collection
                                ? collection.contains(audience) : audience.equals(value)));
            }
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
            return decoder;
        }

        @Bean
        @ConditionalOnMissingBean
        public JwtAuthenticationConverter jwtAuthenticationConverter(JwtClaimsMapper mapper) {
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(jwt -> {
                List<GrantedAuthority> authorities = new ArrayList<>();
                for (String authority : AccessTokenRules.authorities(mapper.map(jwt))) {
                    authorities.add(new SimpleGrantedAuthority(authority));
                }
                return authorities;
            });
            return converter;
        }

        @Bean
        @ConditionalOnMissingBean
        public SecurityDenialAudit securityDenialAudit(
                ObjectProvider<SecurityAuditSink> sinks,
                ObjectProvider<IdGenerator<String>> idGenerators,
                CallerProvider callers,
                BizSecurityProperties properties) {
            return new SecurityDenialAudit(sinks, idGenerators, callers, properties);
        }

        @Bean
        @ConditionalOnMissingBean
        public JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(
                ObjectProvider<JsonMapper> json, SecurityDenialAudit audit) {
            return new JsonAuthenticationEntryPoint(jsonMapper(json), audit);
        }

        @Bean
        @ConditionalOnMissingBean
        public JsonAccessDeniedHandler jsonAccessDeniedHandler(
                ObjectProvider<JsonMapper> json, SecurityDenialAudit audit) {
            return new JsonAccessDeniedHandler(jsonMapper(json), audit);
        }

        @Bean
        @ConditionalOnMissingBean
        public BizSecurityExceptionHandler bizSecurityExceptionHandler() {
            return new BizSecurityExceptionHandler();
        }

        /**
         * CORS：默认不放开任何来源；必须显式配置 {@code seedj.biz.cors.allowed-origins}。
         * 生产 profile 下空配置启动失败，避免默认 {@code *} 泄漏到生产。
         *
         * @param properties 安全配置
         * @param environment Spring 环境
         * @return CORS 来源
         */
        @Bean
        @ConditionalOnMissingBean
        public CorsConfigurationSource corsConfigurationSource(BizSecurityProperties properties,
                                                               Environment environment) {
            if (environment.matchesProfiles("prod") && properties.corsAllowedOrigins().isEmpty()) {
                throw new IllegalStateException("seedj.biz.cors.allowed-origins is required"
                        + " in the prod profile; refusing to run with default CORS");
            }
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOriginPatterns(properties.corsAllowedOrigins());
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(false);

            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", config);
            return source;
        }

        /**
         * 降级开关守卫：degraded-mode / dev-auth 只允许 local/test。
         *
         * @param environment Spring 环境
         * @return 启动期守卫
         */
        @Bean
        @ConditionalOnMissingBean
        public cn.byteforce.seedj.starter.config.DegradedModeGuard degradedModeGuard(
                Environment environment) {
            return new cn.byteforce.seedj.starter.config.DegradedModeGuard(environment);
        }

        private static JsonMapper jsonMapper(ObjectProvider<JsonMapper> provider) {
            return Objects.requireNonNullElseGet(provider.getIfAvailable(),
                    () -> JsonMapper.builder().build());
        }

        private static String resolveIssuer(BizSecurityProperties properties,
                                            OAuth2ResourceServerProperties bootProperties) {
            String configured = properties.issuerUri();
            if (configured != null) {
                return configured;
            }
            String standard = bootProperties == null ? null
                    : bootProperties.getJwt().getIssuerUri();
            if (standard != null && !standard.isBlank()) {
                return standard;
            }
            throw new IllegalStateException("seedj.biz requires a token issuer: configure "
                    + "spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "or seedj.biz.issuer-uri");
        }
    }
}
