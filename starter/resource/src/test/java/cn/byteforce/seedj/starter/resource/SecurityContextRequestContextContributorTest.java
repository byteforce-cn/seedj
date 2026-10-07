package cn.byteforce.seedj.starter.resource;

import cn.byteforce.seedj.core.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SecurityContextRequestContextContributor} 契约测试：claim 映射、缺失与非法值语义。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class SecurityContextRequestContextContributorTest {

    private final SecurityContextRequestContextContributor contributor =
            new SecurityContextRequestContextContributor(new ResourceServerProperties.Claims());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    @Test
    void mapsJwtClaimsIntoRequestContext() {
        authenticate(Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "42")
                .claim("tenantId", 7L)
                .claim("preferred_username", "alice")
                .subject("sub-1")
                .build());

        contributor.contribute();

        assertThat(RequestContext.userId()).contains(42L);
        assertThat(RequestContext.tenantId()).contains(7L);
        assertThat(RequestContext.username()).contains("alice");
    }

    @Test
    void fallsBackToSubjectWhenUsernameClaimMissing() {
        authenticate(Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("sub-2")
                .build());

        contributor.contribute();

        assertThat(RequestContext.userId()).isEmpty();
        assertThat(RequestContext.tenantId()).isEmpty();
        assertThat(RequestContext.username()).contains("sub-2");
    }

    @Test
    void invalidNumericClaimIsIgnoredWithoutFailure() {
        authenticate(Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", "not-a-number")
                .claim("tenantId", "8")
                .subject("sub-3")
                .build());

        contributor.contribute();

        assertThat(RequestContext.userId()).isEmpty();
        assertThat(RequestContext.tenantId()).contains(8L);
        assertThat(RequestContext.username()).contains("sub-3");
    }

    @Test
    void nonJwtAuthenticationIsIgnored() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        contributor.contribute();

        assertThat(RequestContext.userId()).isEmpty();
        assertThat(RequestContext.username()).isEmpty();
    }

    @Test
    void customClaimNamesAreHonored() {
        ResourceServerProperties.Claims claims = new ResourceServerProperties.Claims();
        claims.setUserId("uid");
        claims.setTenantId("tid");
        claims.setUsername("uname");
        SecurityContextRequestContextContributor custom = new SecurityContextRequestContextContributor(claims);

        authenticate(Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("uid", "1")
                .claim("tid", "2")
                .claim("uname", "bob")
                .subject("sub-4")
                .build());

        custom.contribute();

        assertThat(RequestContext.userId()).contains(1L);
        assertThat(RequestContext.tenantId()).contains(2L);
        assertThat(RequestContext.username()).contains("bob");
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
