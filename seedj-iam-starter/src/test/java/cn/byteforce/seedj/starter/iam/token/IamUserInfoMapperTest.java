package cn.byteforce.seedj.starter.iam.token;

import cn.byteforce.seedj.iam.IamGroup;
import cn.byteforce.seedj.iam.IamPermission;
import cn.byteforce.seedj.iam.IamRole;
import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamPasswordHasher;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamAuthorizationRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamGroupRepository;
import cn.byteforce.seedj.infra.local.iam.InMemoryIamUserRepository;
import cn.byteforce.seedj.starter.iam.identity.IamAuthorizationService;
import cn.byteforce.seedj.starter.iam.identity.IamPasswordHasherAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcUserInfoAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

class IamUserInfoMapperTest {

    private final InMemoryIamUserRepository users = new InMemoryIamUserRepository();
    private final InMemoryIamGroupRepository groups = new InMemoryIamGroupRepository();
    private final InMemoryIamAuthorizationRepository authorization =
            new InMemoryIamAuthorizationRepository();
    private final IamPasswordHasher hasher = new IamPasswordHasherAdapter(
            PasswordEncoderFactories.createDelegatingPasswordEncoder());
    private final IamAuthorizationService authorizationService =
            new IamAuthorizationService(authorization, groups, Duration.ofSeconds(5));

    @Test
    void mapsFullProfileAndAuthorizationClaims() {
        IamUser alice = users.create(IamUser.create("u-alice", "acme", "alice", "Alice Ng",
                "alice@example.com", true), hasher.encode("alice-password"));
        IamGroup group = groups.create(IamGroup.create("g-1", "acme", "sales", null,
                Set.of(alice.id())));
        IamRole role = authorization.createRole(
                IamRole.create("r-reader", "acme", "reader", "Reader", "", null, false));
        authorization.savePermission(IamPermission.of("acme", "order", "read", "Read orders"));
        authorization.grantPermission(role.id(), "order:read");
        authorization.assignRoleToUser(alice.id(), role.id(), Instant.now(), "test");
        IamUserInfoMapper mapper = new IamUserInfoMapper(users, authorizationService, "acme");

        OidcUserInfo info = mapper.apply(context("alice"));

        Map<String, Object> claims = info.getClaims();
        assertThat(info.getSubject()).isEqualTo(alice.id());
        assertThat(claims).containsEntry("preferred_username", "alice")
                .containsEntry("name", "Alice Ng")
                .containsEntry("email", "alice@example.com")
                .containsEntry("email_verified", false)
                .containsEntry("tenant_id", "acme");
        assertThat(claims.get("roles")).asInstanceOf(LIST).containsExactly("reader");
        assertThat(claims.get("groups")).asInstanceOf(LIST)
                .containsExactly(group.displayName());
        assertThat(claims.get("permissions")).asInstanceOf(LIST).containsExactly("order:read");
    }

    @Test
    void omitsProfileAndAuthorizationClaimsWhenAbsent() {
        users.create(IamUser.create("u-bob", "acme", "bob", null, null, true), null);
        IamUserInfoMapper mapper = new IamUserInfoMapper(users, authorizationService, "acme");

        OidcUserInfo info = mapper.apply(context("bob"));

        Map<String, Object> claims = info.getClaims();
        assertThat(info.getSubject()).isEqualTo("u-bob");
        assertThat(claims).containsOnlyKeys("sub", "preferred_username", "tenant_id");
    }

    @Test
    void fallsBackToPrincipalNameForUnknownUser() {
        IamUserInfoMapper mapper = new IamUserInfoMapper(users, authorizationService, "acme");

        OidcUserInfo info = mapper.apply(context("ghost"));

        assertThat(info.getSubject()).isEqualTo("ghost");
        assertThat(info.getClaims()).containsOnlyKeys("sub");
    }

    private OidcUserInfoAuthenticationContext context(String principalName) {
        Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
                principalName, "n/a", List.of());
        OAuth2Authorization authorizationRecord =
                OAuth2Authorization.withRegisteredClient(client())
                        .principalName(principalName)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .build();
        return OidcUserInfoAuthenticationContext
                .with(new OidcUserInfoAuthenticationToken(principal))
                .accessToken(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token",
                        Instant.now(), Instant.now().plusSeconds(600)))
                .authorization(authorizationRecord)
                .build();
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("client-1")
                .clientId("web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/web")
                .build();
    }
}
