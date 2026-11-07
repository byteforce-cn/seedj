package cn.byteforce.seedj.starter.iam.token;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

class IamTokenCustomizerTest {

    private static final String ACCESS_TOKEN = OAuth2TokenType.ACCESS_TOKEN.getValue();

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final InMemoryIamAuthorizationRepository authorization =
            new InMemoryIamAuthorizationRepository();
    private final IamPasswordHasher hasher =
            new IamPasswordHasherAdapter(
                    PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final IamAuthorizationService authorizationService =
            new IamAuthorizationService(authorization, groups, Duration.ofSeconds(5));
    private final IamUser alice =
            users.create(
                    IamUser.create(
                            "u-alice", "acme", "alice", "Alice Ng", "alice@example.com", true),
                    hasher.encode("alice-password"));

    @Test
    void accessTokenCarriesIamIdentityRolesGroupsAndPermissions() {
        grantRole("r-reader", "reader", "order:read");
        IamGroup group =
                groups.create(IamGroup.create("g-1", "acme", "sales", null, Set.of(alice.id())));
        IamTokenCustomizer customizer =
                customizer(properties(true, "https://api.example.com"), "acme");

        JwtEncodingContext context = accessTokenContext(ACCESS_TOKEN, "alice");
        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsString("sub")).isEqualTo(alice.id());
        assertThat(claims.getClaimAsString("user_id")).isEqualTo(alice.id());
        assertThat(claims.getClaimAsString("preferred_username")).isEqualTo("alice");
        assertThat(claims.getClaimAsString("tenant_id")).isEqualTo("acme");
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("reader");
        assertThat(claims.getClaimAsStringList("permissions")).containsExactly("order:read");
        assertThat(claims.getClaimAsStringList("groups")).containsExactly(group.displayName());
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("pwd");
        assertThat(claims.getClaimAsString("acr")).isEqualTo("urn:seedj:acr:pwd");
        assertThat((Object) claims.getClaim("auth_time")).isInstanceOf(Date.class);
        assertThat(claims.getAudience()).containsExactly("https://api.example.com");
        assertThat(context.getJwsHeader().build().getType()).isEqualTo("at+jwt");
    }

    @Test
    void idTokenOmitsPermissionsAudienceAndAccessTokenType() {
        grantRole("r-reader", "reader", "order:read");
        IamTokenCustomizer customizer =
                customizer(properties(true, "https://api.example.com"), "acme");

        JwtEncodingContext context = accessTokenContext(OidcParameterNames.ID_TOKEN, "alice");
        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("reader");
        assertThat((Object) claims.getClaim("permissions")).isNull();
        assertThat(claims.getAudience()).isNull();
        assertThat(context.getJwsHeader().build().getType()).isNull();
    }

    @Test
    void ignoresOtherTokenTypes() {
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        JwtEncodingContext context =
                accessTokenContext(OAuth2TokenType.REFRESH_TOKEN.getValue(), "alice");

        customizer.customize(context);

        // Only the pre-existing claim survives: the customizer ignores this token type.
        assertThat(context.getClaims().build().getClaims()).containsOnlyKeys("iss");
    }

    @Test
    void fallsBackToPrincipalAuthoritiesWithoutIamUser() {
        Authentication principal =
                new UsernamePasswordAuthenticationToken(
                        "external",
                        "n/a",
                        List.of(
                                new SimpleGrantedAuthority("ROLE_service"),
                                new SimpleGrantedAuthority("PERM_report:read"),
                                new SimpleGrantedAuthority("GROUP_ops"),
                                new SimpleGrantedAuthority("AMR_mfa"),
                                new SimpleGrantedAuthority("ACR_urn:seedj:acr:mfa"),
                                new SimpleGrantedAuthority("AUTH_TIME_" + 1_700_000_000L),
                                new SimpleGrantedAuthority("SCOPE_read")));
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        JwtEncodingContext context = accessTokenContext(ACCESS_TOKEN, "external", principal);

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsString("sub")).isNull();
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("service");
        assertThat(claims.getClaimAsStringList("permissions")).containsExactly("report:read");
        assertThat(claims.getClaimAsStringList("groups")).containsExactly("ops");
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("mfa");
        assertThat(claims.getClaimAsString("acr")).isEqualTo("urn:seedj:acr:mfa");
        assertThat((Object) claims.getClaim("auth_time"))
                .isEqualTo(Date.from(Instant.ofEpochSecond(1_700_000_000L)));
    }

    @Test
    void ignoresMalformedAuthTimeAuthorityAndDefaultsAmr() {
        Authentication principal =
                new UsernamePasswordAuthenticationToken(
                        "external",
                        "n/a",
                        List.of(new SimpleGrantedAuthority("AUTH_TIME_not-a-number")));
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        JwtEncodingContext context = accessTokenContext(ACCESS_TOKEN, "external", principal);

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("pwd");
        assertThat(claims.getClaimAsString("acr")).isEqualTo("urn:seedj:acr:pwd");
        assertThat((Object) claims.getClaim("auth_time")).isInstanceOf(Date.class);
    }

    @Test
    void omitsPermissionsWhenDisabledButKeepsRoles() {
        grantRole("r-reader", "reader", "order:read");
        IamTokenCustomizer customizer = customizer(properties(false, null), "acme");
        JwtEncodingContext context = accessTokenContext(ACCESS_TOKEN, "alice");

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsStringList("roles")).containsExactly("reader");
        assertThat((Object) claims.getClaim("permissions")).isNull();
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("pwd");
    }

    @Test
    void addsDeviceIdAndKeepsExistingAuthTime() {
        Instant authTime = Instant.parse("2026-01-01T00:00:00Z");
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        OAuth2Authorization authorization =
                OAuth2Authorization.withRegisteredClient(client())
                        .principalName("alice")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .attribute("device_id", "device-1")
                        .build();
        JwtEncodingContext context =
                JwtEncodingContext.with(
                                JwsHeader.with(SignatureAlgorithm.RS256),
                                JwtClaimsSet.builder().claim("auth_time", Date.from(authTime)))
                        .tokenType(new OAuth2TokenType(ACCESS_TOKEN))
                        .principal(principal("alice", List.of()))
                        .authorization(authorization)
                        .build();

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getClaimAsString("device_id")).isEqualTo("device-1");
        assertThat((Object) claims.getClaim("auth_time")).isEqualTo(Date.from(authTime));
    }

    @Test
    void ignoresBlankDeviceIdAndNullPrincipal() {
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        OAuth2Authorization authorization =
                OAuth2Authorization.withRegisteredClient(client())
                        .principalName("alice")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .attribute("device_id", "  ")
                        .build();
        JwtEncodingContext context =
                JwtEncodingContext.with(
                                JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())
                        .tokenType(new OAuth2TokenType(ACCESS_TOKEN))
                        .authorization(authorization)
                        .build();

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat((Object) claims.getClaim("device_id")).isNull();
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("pwd");
    }

    @Test
    void skipsGroupsAndRolesWhenNoneAreGranted() {
        IamTokenCustomizer customizer = customizer(properties(true, null), "acme");
        JwtEncodingContext context = accessTokenContext(ACCESS_TOKEN, "alice");

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat((Object) claims.getClaim("roles")).isNull();
        assertThat((Object) claims.getClaim("groups")).isNull();
        assertThat((Object) claims.getClaim("permissions")).isNull();
    }

    private IamTokenCustomizer customizer(IamProperties properties, String tenantId) {
        return new IamTokenCustomizer(users, authorizationService, properties, tenantId);
    }

    private void grantRole(String roleId, String code, String permissionCode) {
        IamRole role =
                authorization.createRole(
                        IamRole.create(roleId, "acme", code, code, "", null, false));
        authorization.savePermission(IamPermission.of("acme", "order", "read", "Read orders"));
        authorization.grantPermission(role.id(), permissionCode);
        authorization.assignRoleToUser(alice.id(), role.id(), Instant.now(), "test");
    }

    private JwtEncodingContext accessTokenContext(String tokenType, String username) {
        return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), baseClaims())
                .tokenType(new OAuth2TokenType(tokenType))
                .principal(principal(username, List.of()))
                .build();
    }

    private JwtEncodingContext accessTokenContext(
            String tokenType, String username, Authentication principal) {
        return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), baseClaims())
                .tokenType(new OAuth2TokenType(tokenType))
                .principal(principal)
                .build();
    }

    private static Authentication principal(String username, List<SimpleGrantedAuthority> roles) {
        return UsernamePasswordAuthenticationToken.authenticated(username, "n/a", roles);
    }

    private static JwtClaimsSet.Builder baseClaims() {
        return JwtClaimsSet.builder().claim("iss", "https://iam.test");
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("client-1")
                .clientId("web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/web")
                .build();
    }

    private static IamProperties properties(boolean includePermissions, String audience) {
        return new IamProperties(
                null,
                null,
                null,
                null,
                null,
                new IamProperties.Token(null, null, null, includePermissions, audience),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
