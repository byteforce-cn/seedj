package cn.byteforce.seedj.starter.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资源服务器 JWT 解码器装配：issuer 解析顺序与 RFC 9068 校验器是否真的挂上。
 *
 * <p>用一个本地 HTTP 授权服务器（JDK {@link HttpServer} + 自签 RSA 密钥）走真实的
 * discovery / JWKS 流程，断言"能解出合法访问令牌、拒绝 ID Token / 缺 audience"，
 * 而不是只断言 bean 存在。
 */
class BizSecurityWebSecurityConfigurationTest {

    private static HttpServer authorizationServer;
    private static String issuer;
    private static RSAKey signingKey;

    private final BizSecurityAutoConfiguration.WebSecurityConfiguration configuration =
            new BizSecurityAutoConfiguration.WebSecurityConfiguration();

    @BeforeAll
    static void startAuthorizationServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyID("test-key")
                .build();

        authorizationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + authorizationServer.getAddress().getPort();
        authorizationServer.createContext("/.well-known/openid-configuration", exchange ->
                respond(exchange, "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer
                        + "/jwks\"}"));
        authorizationServer.createContext("/jwks", exchange ->
                respond(exchange, new JWKSet(signingKey.toPublicJWK()).toString()));
        authorizationServer.start();
    }

    @AfterAll
    static void stopAuthorizationServer() {
        authorizationServer.stop(0);
    }

    @Test
    @DisplayName("issuer 取自 seedj.biz.issuer-uri，并接受合法的 at+jwt 访问令牌")
    void usesSeedjIssuerAndAcceptsAccessToken() {
        JwtDecoder decoder = decoder(properties(issuer, null), null);

        Jwt jwt = decoder.decode(token("at+jwt", "billing-api"));

        assertThat(jwt.getClaimAsString("iss")).isEqualTo(issuer);
        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getAudience()).containsExactly("billing-api");
    }

    @Test
    @DisplayName("seedj.biz.issuer-uri 未配置时回落到 Spring Boot 标准键")
    void fallsBackToBootIssuer() {
        OAuth2ResourceServerProperties boot = new OAuth2ResourceServerProperties();
        boot.getJwt().setIssuerUri(issuer);

        JwtDecoder decoder = decoder(properties(null, null), boot);

        assertThat(decoder.decode(token("at+jwt", "billing-api")).getClaimAsString("iss"))
                .isEqualTo(issuer);
    }

    @Test
    @DisplayName("两处都没配 issuer 时启动期失败并指明可配置键")
    void missingIssuerFailsFast() {
        assertThatThrownBy(() -> decoder(properties(null, null), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri")
                .hasMessageContaining("seedj.biz.issuer-uri");
    }

    @Test
    @DisplayName("ID Token（typ=JWT）不能当访问令牌用")
    void rejectsIdToken() {
        JwtDecoder decoder = decoder(properties(issuer, null), null);

        assertThatThrownBy(() -> decoder.decode(token("JWT", "billing-api")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("缺少 aud 的令牌被拒绝")
    void rejectsMissingAudience() {
        JwtDecoder decoder = decoder(properties(issuer, null), null);

        assertThatThrownBy(() -> decoder.decode(token("at+jwt", null)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("配置 audience 后逐项匹配：命中放行、其他拒绝")
    void enforcesConfiguredAudience() {
        JwtDecoder decoder = decoder(properties(issuer, "billing-api"), null);

        assertThat(decoder.decode(token("at+jwt", "billing-api")).getAudience())
                .containsExactly("billing-api");
        assertThatThrownBy(() -> decoder.decode(token("at+jwt", "other-api")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("令牌 claim 映射为 SCOPE_/ROLE_ 权限串")
    void mapsAuthorities() {
        JwtAuthenticationConverter converter =
                configuration.jwtAuthenticationConverter(new JwtClaimsMapper());
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("typ", "at+jwt")
                .subject("user-1")
                .issuer(issuer)
                .audience(List.of("billing-api"))
                .claim("scope", "orders.read")
                .claim("roles", List.of("admin"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        List<String> authorities = converter.convert(jwt).getAuthorities().stream()
                .map(Object::toString)
                .toList();

        assertThat(authorities)
                .filteredOn(authority -> authority.startsWith("SCOPE_")
                        || authority.startsWith("ROLE_"))
                .containsExactlyInAnyOrder("SCOPE_orders.read", "ROLE_admin");
        // Spring Security 7 另加一个认证因子权限，不属于令牌映射的一部分。
        assertThat(authorities).anyMatch(authority -> authority.contains("FACTOR_BEARER"));
    }

    private JwtDecoder decoder(BizSecurityProperties properties,
                               OAuth2ResourceServerProperties boot) {
        return configuration.jwtDecoder(properties, providerOf(boot));
    }

    private static BizSecurityProperties properties(String issuerUri, String audience) {
        return new BizSecurityProperties(null, issuerUri, audience, null, null, null, null);
    }

    private static String token(String type, String audience) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-1")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("scope", "orders.read");
        if (audience != null) {
            claims.audience(audience);
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID("test-key")
                        .type(new JOSEObjectType(type))
                        .build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(signingKey));
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign test token", e);
        }
        return jwt.serialize();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<OAuth2ResourceServerProperties> providerOf(
            OAuth2ResourceServerProperties properties) {
        ObjectProvider<OAuth2ResourceServerProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }
}
