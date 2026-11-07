package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.starter.iam.IamProperties;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.TestIdGenerators;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IamClientRegistrationTest {

    private static final IdGenerator<String> IDS = TestIdGenerators.sequential();

    @Test
    void inMemoryRepositoryIndexesByIdAndClientId() {
        IamInMemoryRegisteredClientRepository repository =
                new IamInMemoryRegisteredClientRepository();
        RegisteredClient client = client("web");

        repository.save(client);

        assertThat(repository.findByClientId("web")).isSameAs(client);
        assertThat(repository.findById(client.getId())).isSameAs(client);
        assertThat(repository.findByClientId("unknown")).isNull();
        assertThat(repository.findById("unknown")).isNull();
    }

    @Test
    void bootstrapRunnerInsertsOnlyMissingClients() {
        IamInMemoryRegisteredClientRepository repository =
                new IamInMemoryRegisteredClientRepository();
        IamProperties properties = new IamProperties(null, null, null, null, null, null, null,
                null, null, null, null, new IamProperties.Demo(true), null, null, List.of());
        RegisteredClientRepository bootstrapTarget = repository;

        new IamClientBootstrapRunner(bootstrapTarget, properties, IDS).afterSingletonsInstantiated();

        assertThat(repository.findByClientId(IamClientFactory.DEFAULT_CLIENT_ID)).isNotNull();
        assertThat(repository.findByClientId(IamClientFactory.TLS_CLIENT_ID)).isNotNull();

        // A second run must not duplicate or fail: existing client ids are skipped.
        RegisteredClient existing = repository.findByClientId(IamClientFactory.DEFAULT_CLIENT_ID);
        new IamClientBootstrapRunner(bootstrapTarget, properties, IDS).afterSingletonsInstantiated();
        assertThat(repository.findByClientId(IamClientFactory.DEFAULT_CLIENT_ID))
                .isSameAs(existing);
    }

    @Test
    void bootstrapRunnerIsNoOpWithoutConfiguredClients() {
        IamInMemoryRegisteredClientRepository repository =
                new IamInMemoryRegisteredClientRepository();
        IamProperties properties = IamProperties.defaults();

        new IamClientBootstrapRunner(repository, properties, IDS).afterSingletonsInstantiated();

        assertThat(repository.findByClientId(IamClientFactory.DEFAULT_CLIENT_ID)).isNull();
    }

    @Test
    void acceptsRfc9068AccessTokensAndRejectsIdTokens() {
        Jwt accessToken = jwt("at+jwt");
        Jwt idToken = jwt("JWT");

        assertThat(IamJwtValidators.accessToken("https://iam.test").validate(accessToken)
                .hasErrors()).isFalse();
        assertThat(IamJwtValidators.accessToken("https://iam.test").validate(idToken)
                .hasErrors()).isTrue();
        assertThat(IamJwtValidators.hasAudience(List.of("api"))).isTrue();
        assertThat(IamJwtValidators.hasAudience(List.of())).isFalse();
        assertThat(IamJwtValidators.hasAudience(null)).isFalse();
    }

    private static Jwt jwt(String type) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("typ", type)
                .issuer("https://iam.test")
                .audience(List.of("api"))
                .subject("alice")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static RegisteredClient client(String clientId) {
        return RegisteredClient.withId("client-" + clientId)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/" + clientId)
                .build();
    }
}
