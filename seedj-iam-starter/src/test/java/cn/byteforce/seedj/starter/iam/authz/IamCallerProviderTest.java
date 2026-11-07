package cn.byteforce.seedj.starter.iam.authz;

import cn.byteforce.seedj.core.security.Caller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IAM 宿主的 CallerProvider：从 SecurityContext 读取权限。
 */
class IamCallerProviderTest {

    private final IamCallerProvider provider = new IamCallerProvider("default");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PERM_ 前缀转为权限，未认证返回空")
    void mapsAuthoritiesAndHandlesAnonymous() {
        assertThat(provider.find()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("svc", "n/a",
                        List.of(new SimpleGrantedAuthority("PERM_hr.attributes.read"),
                                new SimpleGrantedAuthority("ROLE_internal"))));

        Caller caller = provider.find().orElseThrow();
        assertThat(caller.username()).isEqualTo("svc");
        assertThat(caller.hasPermission("hr.attributes.read")).isTrue();
        assertThat(caller.hasRole("internal")).isTrue();
    }

    @Test
    @DisplayName("匿名与非认证令牌都返回空")
    void anonymousAndUnauthenticatedAreEmpty() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(provider.find()).isEmpty();

        UsernamePasswordAuthenticationToken unauthenticated =
                new UsernamePasswordAuthenticationToken("svc", "n/a");
        unauthenticated.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertThat(provider.find()).isEmpty();
    }

    @Test
    @DisplayName("GROUP_ 前缀转为组，缺省 amr/acr 使用默认值")
    void mapsGroupAuthoritiesWithDefaultAmr() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("svc", "n/a",
                        List.of(new SimpleGrantedAuthority("GROUP_finance"))));

        Caller caller = provider.find().orElseThrow();

        assertThat(caller.groups()).containsExactly("finance");
        assertThat(caller.amr()).containsExactly(Caller.DEFAULT_AMR);
        assertThat(caller.acr()).isEqualTo(Caller.DEFAULT_ACR);
        assertThat(caller.id()).isEqualTo("svc");
    }

    @Test
    @DisplayName("JWT：user_id 覆盖主体，声明并入角色/权限/组/属性")
    void jwtClaimsExtendCaller() {
        Jwt jwt = Jwt.withTokenValue("jwt-value")
                .header("alg", "none")
                .subject("alice")
                .claim("user_id", "u-9")
                .claim("roles", List.of("auditor"))
                .claim("permissions", List.of("audit.read"))
                .claim("groups", List.of("compliance"))
                .claim("tenant", "acme")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_reader"),
                        new SimpleGrantedAuthority("PERM_order.read"))));

        Caller caller = provider.find().orElseThrow();

        assertThat(caller.id()).isEqualTo("u-9");
        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.roles()).containsExactlyInAnyOrder("reader", "auditor");
        assertThat(caller.permissions()).containsExactlyInAnyOrder("order.read", "audit.read");
        assertThat(caller.groups()).containsExactly("compliance");
        assertThat(caller.attributes()).containsEntry("tenant", "acme");
    }

    @Test
    @DisplayName("JWT 缺 user_id 时以 sub 作为主体")
    void jwtWithoutUserIdUsesSubject() {
        Jwt jwt = Jwt.withTokenValue("jwt-value")
                .header("alg", "none")
                .subject("bob")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_read"))));

        Caller caller = provider.find().orElseThrow();

        assertThat(caller.id()).isEqualTo("bob");
        assertThat(caller.username()).isEqualTo("bob");
        assertThat(caller.roles()).isEmpty();
        assertThat(caller.permissions()).isEmpty();
        assertThat(caller.groups()).isEmpty();
    }
}
