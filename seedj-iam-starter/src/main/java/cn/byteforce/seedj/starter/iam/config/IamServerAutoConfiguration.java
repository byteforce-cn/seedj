package cn.byteforce.seedj.starter.iam.config;

import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamTokenRevoker;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import cn.byteforce.seedj.starter.iam.nativeauth.NativeAuthAuthenticationConverter;
import cn.byteforce.seedj.starter.iam.nativeauth.NativeAuthAuthenticationProvider;
import cn.byteforce.seedj.starter.iam.nativeauth.NativeAuthErrorResponseHandler;
import cn.byteforce.seedj.starter.iam.security.IamClientBootstrapRunner;
import cn.byteforce.seedj.starter.iam.security.IamInMemoryRegisteredClientRepository;
import cn.byteforce.seedj.starter.iam.security.IamRevocableAuthorizationService;
import cn.byteforce.seedj.starter.iam.security.NativeAuthGrantType;
import cn.byteforce.seedj.starter.iam.token.IamTokenCustomizer;
import cn.byteforce.seedj.starter.iam.token.IamUserInfoMapper;
import cn.byteforce.seedj.starter.iam.web.AuthorizationRequestConformanceFilter;
import cn.byteforce.seedj.starter.iam.web.EndpointParameterConformanceFilter;
import cn.byteforce.seedj.starter.iam.web.IamMfaAuthenticationSuccessHandler;
import cn.byteforce.seedj.starter.iam.web.IdTokenIntrospectionGuard;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OidcConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2ErrorAuthenticationFailureHandler;
import org.springframework.security.oauth2.server.authorization.web.authentication.PublicClientAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.X509ClientCertificateAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * OAuth2/OIDC authorization-server wiring plus the SCIM/IAM/default security chains. The IAM module
 * owns this configuration so the aggregate starter can keep the protocol implementation optional.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@Import(IamServerAutoConfiguration.ActuatorSecurityConfiguration.class)
public class IamServerAutoConfiguration {

    private static final List<String> DEFAULT_SCOPES =
            List.of(
                    "openid",
                    "profile",
                    "email",
                    "address",
                    "phone",
                    "read",
                    "offline_access",
                    "scim.users.read",
                    "scim.users.write",
                    "scim.groups.read",
                    "scim.groups.write");
    private static final List<String> SUPPORTED_CLAIMS =
            List.of(
                    "sub",
                    "iss",
                    "aud",
                    "exp",
                    "iat",
                    "auth_time",
                    "nonce",
                    "azp",
                    "sid",
                    "jti",
                    "user_id",
                    "preferred_username",
                    "tenant_id",
                    "roles",
                    "permissions",
                    "groups",
                    "amr",
                    "acr",
                    "device_id");
    private static final List<String> SIGNING_ALGORITHMS =
            List.of(
                    "RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384",
                    "ES512");

    // ---------------------------------------------------------------------
    // Core protocol collaborators
    // ---------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    public IamIssuerResolver iamIssuerResolver(
            org.springframework.core.env.Environment environment, IamProperties properties) {
        return new IamIssuerResolver(environment, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthorizationServerSettings authorizationServerSettings(IamIssuerResolver issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer.issuer()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisteredClientRepository registeredClientRepository(
            IamProperties properties, ObjectProvider<JdbcTemplate> jdbc) {
        if (properties.store() == IamProperties.Store.JDBC) {
            return new JdbcRegisteredClientRepository(jdbc.getObject());
        }
        return new IamInMemoryRegisteredClientRepository();
    }

    @Bean
    @DependsOn("iamInfrastructureInitializer")
    @ConditionalOnMissingBean
    public OAuth2AuthorizationService authorizationService(
            IamProperties properties,
            RegisteredClientRepository clients,
            ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<InMemoryIamTokenRevoker> revoker) {
        if (properties.store() == IamProperties.Store.JDBC) {
            return new JdbcOAuth2AuthorizationService(jdbc.getObject(), clients);
        }
        return new IamRevocableAuthorizationService(
                new InMemoryOAuth2AuthorizationService(), revoker.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            IamProperties properties,
            ObjectProvider<JdbcTemplate> jdbc,
            RegisteredClientRepository clients) {
        if (properties.store() == IamProperties.Store.JDBC) {
            return new JdbcOAuth2AuthorizationConsentService(jdbc.getObject(), clients);
        }
        return new InMemoryOAuth2AuthorizationConsentService();
    }

    @Bean
    @ConditionalOnMissingBean
    public JWKSource<SecurityContext> jwkSource(IamSigningKeyStore store) {
        return (selector, context) -> {
            List<JWK> keys =
                    store.keys().stream()
                            .map(
                                    key -> {
                                        try {
                                            String json =
                                                    key.canSign()
                                                            ? key.privateJwk()
                                                            : key.publicJwk();
                                            return JWK.parse(json);
                                        } catch (java.text.ParseException e) {
                                            throw new IllegalStateException(
                                                    "invalid stored JWK for kid " + key.kid(), e);
                                        }
                                    })
                            .toList();
            return selector.select(new JWKSet(keys));
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public OAuth2TokenGenerator<?> oauth2TokenGenerator(
            JWKSource<SecurityContext> jwkSource, IamTokenCustomizer customizer) {
        JwtGenerator jwt = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwt.setJwtCustomizer(customizer);
        return new DelegatingOAuth2TokenGenerator(
                jwt, new OAuth2AccessTokenGenerator(), new OAuth2RefreshTokenGenerator());
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, IamIssuerResolver issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        decoder.setJwtValidator(
                cn.byteforce.seedj.starter.iam.security.IamJwtValidators.accessToken(
                        issuer.issuer()));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    public IamTokenCustomizer iamTokenCustomizer(
            IamUserRepository users,
            IamAuthorizationService authorization,
            IamProperties properties) {
        return new IamTokenCustomizer(users, authorization, properties, properties.tenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public IamUserInfoMapper iamUserInfoMapper(
            IamUserRepository users,
            IamAuthorizationService authorization,
            IamProperties properties) {
        return new IamUserInfoMapper(users, authorization, properties.tenantId());
    }

    @Bean
    @ConditionalOnMissingBean
    public IamClientBootstrapRunner iamClientBootstrapRunner(
            RegisteredClientRepository repository,
            IamProperties properties,
            IdGenerator<String> idGenerator) {
        return new IamClientBootstrapRunner(repository, properties, idGenerator);
    }

    // ---------------------------------------------------------------------
    // Security chains
    // ---------------------------------------------------------------------

    @Bean
    @Order(1)
    @ConditionalOnMissingBean(name = "authorizationServerSecurityFilterChain")
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository registeredClients,
            OAuth2AuthorizationConsentService consentService,
            OAuth2AuthorizationService authorizationService,
            AuthorizationServerSettings settings,
            IamProperties properties,
            IamUserInfoMapper userInfoMapper,
            ObjectProvider<NativeAuthAuthenticationConverter> nativeConverter,
            ObjectProvider<NativeAuthAuthenticationProvider> nativeProvider)
            throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        MediaTypeRequestMatcher htmlMatcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        http.addFilterBefore(
                new AuthorizationRequestConformanceFilter(
                        registeredClients, consentService, settings),
                org.springframework.security.web.authentication.preauth
                        .AbstractPreAuthenticatedProcessingFilter.class);
        http.addFilterBefore(
                new EndpointParameterConformanceFilter(settings),
                org.springframework.security.web.authentication.preauth
                        .AbstractPreAuthenticatedProcessingFilter.class);

        http.csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()));

        OAuth2ErrorAuthenticationFailureHandler oauth2ErrorHandler =
                new OAuth2ErrorAuthenticationFailureHandler();
        http.apply(authorizationServer)
                .oidc(oidc -> configureOidc(oidc, properties, userInfoMapper))
                .authorizationServerMetadataEndpoint(
                        metadata ->
                                metadata.authorizationServerMetadataCustomizer(
                                        builder -> {
                                            builder.claim("scopes_supported", DEFAULT_SCOPES);
                                            builder.claim("claims_supported", SUPPORTED_CLAIMS);
                                            builder.claim(
                                                    "response_modes_supported", List.of("query"));
                                            builder.claim(
                                                    "token_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            builder.claim(
                                                    "revocation_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            builder.claim(
                                                    "introspection_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            if (properties.nativeAuth().enabled()) {
                                                builder.grantType(
                                                        NativeAuthGrantType.NATIVE_AUTH.getValue());
                                            }
                                        }))
                .tokenIntrospectionEndpoint(
                        introspection ->
                                introspection.authenticationProvider(
                                        new IdTokenIntrospectionGuard(authorizationService)))
                .tokenEndpoint(
                        token -> {
                            nativeConverter.ifAvailable(token::accessTokenRequestConverter);
                            nativeProvider.ifAvailable(token::authenticationProvider);
                            token.errorResponseHandler(new NativeAuthErrorResponseHandler());
                        })
                .clientAuthentication(
                        clientAuthentication ->
                                clientAuthentication
                                        .authenticationConverters(
                                                converters -> {
                                                    org.springframework.security.web.authentication
                                                                    .AuthenticationConverter
                                                            x509 =
                                                                    converters.stream()
                                                                            .filter(
                                                                                    X509ClientCertificateAuthenticationConverter
                                                                                                    .class
                                                                                            ::isInstance)
                                                                            .findFirst()
                                                                            .orElse(null);
                                                    if (x509 == null) {
                                                        return;
                                                    }
                                                    converters.remove(x509);
                                                    int publicClientIndex = -1;
                                                    for (int i = 0; i < converters.size(); i++) {
                                                        if (converters.get(i)
                                                                instanceof
                                                                PublicClientAuthenticationConverter) {
                                                            publicClientIndex = i;
                                                            break;
                                                        }
                                                    }
                                                    if (publicClientIndex >= 0) {
                                                        converters.add(publicClientIndex, x509);
                                                    } else {
                                                        converters.add(x509);
                                                    }
                                                })
                                        .errorResponseHandler(
                                                (request, response, exception) -> {
                                                    String authorization =
                                                            request.getHeader(
                                                                    HttpHeaders.AUTHORIZATION);
                                                    boolean basicAttempted =
                                                            authorization != null
                                                                    && authorization.regionMatches(
                                                                            true, 0, "Basic ", 0,
                                                                            6);
                                                    boolean invalidClient =
                                                            exception
                                                                            instanceof
                                                                            OAuth2AuthenticationException
                                                                                            oauth2
                                                                    && OAuth2ErrorCodes
                                                                            .INVALID_CLIENT
                                                                            .equals(
                                                                                    oauth2.getError()
                                                                                            .getErrorCode());
                                                    if (basicAttempted && invalidClient) {
                                                        response.setHeader(
                                                                HttpHeaders.WWW_AUTHENTICATE,
                                                                "Basic realm=\"oauth2/client\"");
                                                        HttpServletResponse wrapper =
                                                                new jakarta.servlet.http
                                                                        .HttpServletResponseWrapper(
                                                                        response) {
                                                                    @Override
                                                                    public void setStatus(
                                                                            int status) {
                                                                        super.setStatus(
                                                                                status
                                                                                                == HttpServletResponse
                                                                                                        .SC_BAD_REQUEST
                                                                                        ? HttpServletResponse
                                                                                                .SC_UNAUTHORIZED
                                                                                        : status);
                                                                    }

                                                                    @Override
                                                                    public void sendError(
                                                                            int status)
                                                                            throws
                                                                                    java.io
                                                                                            .IOException {
                                                                        super.sendError(
                                                                                status
                                                                                                == HttpServletResponse
                                                                                                        .SC_BAD_REQUEST
                                                                                        ? HttpServletResponse
                                                                                                .SC_UNAUTHORIZED
                                                                                        : status);
                                                                    }

                                                                    @Override
                                                                    public void sendError(
                                                                            int status,
                                                                            String message)
                                                                            throws
                                                                                    java.io
                                                                                            .IOException {
                                                                        super.sendError(
                                                                                status
                                                                                                == HttpServletResponse
                                                                                                        .SC_BAD_REQUEST
                                                                                        ? HttpServletResponse
                                                                                                .SC_UNAUTHORIZED
                                                                                        : status,
                                                                                message);
                                                                    }
                                                                };
                                                        oauth2ErrorHandler.onAuthenticationFailure(
                                                                request, wrapper, exception);
                                                        return;
                                                    }
                                                    oauth2ErrorHandler.onAuthenticationFailure(
                                                            request, response, exception);
                                                }));

        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/oauth2/authorize", "/userinfo")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        ex ->
                                ex.defaultAuthenticationEntryPointFor(
                                                new cn.byteforce.seedj.starter.iam.web
                                                        .IamBearerTokenEntryPoint(),
                                                IamServerAutoConfiguration::isUserInfo)
                                        .defaultAuthenticationEntryPointFor(
                                                new LoginUrlAuthenticationEntryPoint("/login"),
                                                htmlMatcher))
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.authenticationEntryPoint(
                                                new cn.byteforce.seedj.starter.iam.web
                                                        .IamBearerTokenEntryPoint())
                                        .jwt(Customizer.withDefaults()));
        return http.build();
    }

    private static boolean isUserInfo(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().endsWith("/userinfo");
    }

    private static void configureOidc(
            OidcConfigurer oidc, IamProperties properties, IamUserInfoMapper userInfoMapper) {
        oidc.providerConfigurationEndpoint(
                        provider ->
                                provider.providerConfigurationCustomizer(
                                        builder -> {
                                            builder.claim("scopes_supported", DEFAULT_SCOPES);
                                            builder.claim("claims_supported", SUPPORTED_CLAIMS);
                                            builder.claim(
                                                    "response_modes_supported", List.of("query"));
                                            builder.claim(
                                                    "token_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            builder.claim(
                                                    "revocation_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            builder.claim(
                                                    "introspection_endpoint_auth_signing_alg_values_supported",
                                                    SIGNING_ALGORITHMS);
                                            if (properties.nativeAuth().enabled()) {
                                                builder.grantType(
                                                        NativeAuthGrantType.NATIVE_AUTH.getValue());
                                            }
                                        }))
                .userInfoEndpoint(userInfo -> userInfo.userInfoMapper(userInfoMapper));
    }

    @Bean
    @Order(2)
    @ConditionalOnMissingBean(name = "scimSecurityFilterChain")
    @ConditionalOnProperty(
            prefix = "seedj.iam.scim",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public SecurityFilterChain scimSecurityFilterChain(
            HttpSecurity http, IamProperties properties, JwtDecoder jwtDecoder) throws Exception {
        String base = properties.scim().basePath();
        http.securityMatcher(base + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                base + "/ServiceProviderConfig",
                                                base + "/Schemas",
                                                base + "/ResourceTypes")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                base + "/Users/**")
                                        .hasAnyAuthority("SCOPE_scim.users.read", "PERM_scim:read")
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                base + "/Groups/**")
                                        .hasAnyAuthority("SCOPE_scim.groups.read", "PERM_scim:read")
                                        .requestMatchers(base + "/Users/**")
                                        .hasAnyAuthority(
                                                "SCOPE_scim.users.write", "PERM_scim:write")
                                        .requestMatchers(base + "/Groups/**")
                                        .hasAnyAuthority(
                                                "SCOPE_scim.groups.write", "PERM_scim:write")
                                        .requestMatchers(base + "/**")
                                        .hasAnyAuthority(
                                                "SCOPE_scim.users.write",
                                                "SCOPE_scim.groups.write",
                                                "PERM_scim:write"))
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.authenticationEntryPoint(
                                                new cn.byteforce.seedj.starter.iam.scim
                                                        .ScimAuthenticationEntryPoint())
                                        .accessDeniedHandler(
                                                new cn.byteforce.seedj.starter.iam.scim
                                                        .ScimAccessDeniedHandler())
                                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    @Order(3)
    @ConditionalOnMissingBean(name = "iamSecurityFilterChain")
    public SecurityFilterChain iamSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {
        MediaTypeRequestMatcher htmlMatcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        http.securityMatcher("/iam/**")
                .csrf(csrf -> csrf.ignoringRequestMatchers(IamServerAutoConfiguration::isBearer))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/iam/mfa/**")
                                        .authenticated()
                                        .requestMatchers("/iam/admin/**")
                                        .hasAnyAuthority(
                                                "PERM_iam:admin", "ROLE_iam-admin", "ROLE_ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.defaultAuthenticationEntryPointFor(
                                        new LoginUrlAuthenticationEntryPoint("/login"),
                                        htmlMatcher))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public SecurityFilterChain defaultSecurityFilterChain(
            HttpSecurity http, IamMfaAuthenticationSuccessHandler successHandler) throws Exception {
        // 浏览器登录链只负责登录/MFA 页面，必须排在所有能力链之后（见 docs/guide/iam-starter.md）。
        // 之前是无 matcher 的 catch-all 且 order=5：聚合了 biz 的宿主里会把业务链整条吃掉。
        http.securityMatcher("/login", "/logout", "/mfa", "/error")
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/login", "/logout", "/mfa", "/error")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .formLogin(form -> form.successHandler(successHandler));
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public IamMfaAuthenticationSuccessHandler iamMfaAuthenticationSuccessHandler(
            IamUserRepository users,
            ObjectProvider<IamTotpService> totp,
            IamProperties properties,
            AuthorizationServerSettings settings) {
        return new IamMfaAuthenticationSuccessHandler(
                users, totp.getIfAvailable(), properties.tenantId(), settings);
    }

    private static boolean isBearer(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    // ---------------------------------------------------------------------
    // Optional actuator chain; kept separate so actuator stays optional.
    // ---------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(
            name =
                    "org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest")
    static class ActuatorSecurityConfiguration {

        @Bean
        @Order(4)
        @ConditionalOnMissingBean(name = "iamActuatorSecurityFilterChain")
        public SecurityFilterChain iamActuatorSecurityFilterChain(HttpSecurity http)
                throws Exception {
            http.securityMatcher(
                            org.springframework.boot.security.autoconfigure.actuate.web.servlet
                                    .EndpointRequest.toAnyEndpoint())
                    .authorizeHttpRequests(
                            auth ->
                                    auth.requestMatchers(
                                                    org.springframework.boot.security.autoconfigure
                                                            .actuate.web.servlet.EndpointRequest.to(
                                                            "health"))
                                            .permitAll()
                                            .anyRequest()
                                            .hasAnyRole("ADMIN", "iam-admin"))
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }
}
