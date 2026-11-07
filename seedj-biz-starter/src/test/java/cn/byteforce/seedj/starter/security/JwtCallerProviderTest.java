package cn.byteforce.seedj.starter.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtCallerProviderTest {

    private final CallerPropagation propagation = new ThreadLocalCallerPropagation();
    private final CallerProvider provider =
            new JwtCallerProvider(propagation, new JwtClaimsMapper(), "t-1");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未认证时返回空（公开端点是正常状态）")
    void anonymousReturnsEmpty() {
        assertThat(provider.find()).isEmpty();
    }

    @Test
    @DisplayName("匿名令牌视为未认证")
    void anonymousTokenReturnsEmpty() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "key",
                                "anonymous",
                                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(provider.find()).isEmpty();
    }

    @Test
    @DisplayName("JWT 令牌映射为 Caller，user_id 优先")
    void mapsJwtClaims() {
        Jwt jwt = jwt();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        Caller caller = provider.find().orElseThrow();

        assertThat(caller.id()).isEqualTo("u-1");
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.tenantId()).isEqualTo("t-1");
        assertThat(caller.roles()).containsExactly("ADMIN");
        assertThat(caller.permissions()).containsExactly("order:read");
        assertThat(caller.hasPermission("order:read")).isTrue();
    }

    @Test
    @DisplayName("非 JWT 认证按前缀还原角色/权限")
    void mapsAuthorityPrefixes() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "bob",
                                "n/a",
                                List.of(
                                        new SimpleGrantedAuthority("ROLE_OPS"),
                                        new SimpleGrantedAuthority("PERM_order:write"),
                                        new SimpleGrantedAuthority("GROUP_sales"))));

        Caller caller = provider.find().orElseThrow();

        assertThat(caller.id()).isEqualTo("bob");
        assertThat(caller.roles()).containsExactly("OPS");
        assertThat(caller.permissions()).containsExactly("order:write");
        assertThat(caller.groups()).containsExactly("sales");
    }

    @Test
    @DisplayName("异步作用域优先于 SecurityContext")
    void propagationWins() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt()));
        Caller async =
                new Caller(
                        "async-user",
                        "async",
                        "t-9",
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        java.util.Set.of(),
                        Caller.DEFAULT_ACR,
                        Map.of());

        try (CallerPropagation.Scope ignored = propagation.open(async)) {
            assertThat(provider.find()).contains(async);
        }

        assertThat(provider.find().orElseThrow().id()).isEqualTo("u-1");
    }

    @Test
    @DisplayName("作用域可嵌套，close 恢复外层身份")
    void scopesNest() {
        Caller outer = caller("outer");
        Caller inner = caller("inner");

        try (CallerPropagation.Scope ignored = propagation.open(outer)) {
            try (CallerPropagation.Scope nested = propagation.open(inner)) {
                assertThat(propagation.current()).contains(inner);
            }
            assertThat(propagation.current()).contains(outer);
        }
        assertThat(propagation.current()).isEmpty();
    }

    private static Caller caller(String id) {
        return new Caller(
                id,
                id,
                "t-1",
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                Caller.DEFAULT_ACR,
                Map.of());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("typ", "at+jwt")
                .claim("iss", "https://iam.test")
                .subject("alice")
                .claim("user_id", "u-1")
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("order:read"))
                .audience(List.of("order-api"))
                .issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
    }
}
