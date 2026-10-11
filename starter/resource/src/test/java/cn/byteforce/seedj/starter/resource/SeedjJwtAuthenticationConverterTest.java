package cn.byteforce.seedj.starter.resource;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeedjJwtAuthenticationConverter} 契约测试：scope/scp 缺省映射与
 * 自定义权限 claim（含可配置 claim 名与关闭开关）的合并行为。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SeedjJwtAuthenticationConverterTest {

    private final SeedjJwtAuthenticationConverter converter =
            new SeedjJwtAuthenticationConverter(new ResourceServerProperties().getClaims());

    @Test
    void mergesAuthoritiesClaimWithScopeAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("scope", "read")
                .claim("authorities", List.of("identity:manage", "hr:manage"))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_read", "identity:manage", "hr:manage");
    }

    @Test
    void missingAuthoritiesClaimKeepsDefaultScopeMapping() {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("scope", "read")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_read")
                .doesNotContain("identity:manage");
    }

    @Test
    void claimNameIsConfigurable() {
        ResourceServerProperties.Claims claims = new ResourceServerProperties().getClaims();
        claims.setAuthorities("perms");
        SeedjJwtAuthenticationConverter custom = new SeedjJwtAuthenticationConverter(claims);
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("perms", List.of("finance:approve"))
                .claim("authorities", List.of("identity:manage"))
                .build();

        AbstractAuthenticationToken token = custom.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("finance:approve")
                .doesNotContain("identity:manage");
    }

    @Test
    void blankClaimNameDisablesExtensionMapping() {
        ResourceServerProperties.Claims claims = new ResourceServerProperties().getClaims();
        claims.setAuthorities("");
        SeedjJwtAuthenticationConverter custom = new SeedjJwtAuthenticationConverter(claims);
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .claim("authorities", List.of("identity:manage"))
                .build();

        AbstractAuthenticationToken token = custom.convert(jwt);

        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("identity:manage");
    }
}
