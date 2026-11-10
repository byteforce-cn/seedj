package cn.byteforce.seedj.starter.iam;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.IamUser;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class IamJdbcAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("iam")
                    .withUsername("iam")
                    .withPassword("iam");

    static DataSource dataSource;

    @BeforeAll
    static void dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
    }

    @AfterAll
    static void stop() {
        // container is managed by Testcontainers
    }

    @Test
    void jdbcContextPersistsUsersAndSasClientsAcrossRestarts() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        WebApplicationContextRunner runner =
                new WebApplicationContextRunner()
                        .withUserConfiguration(TestApplication.class)
                        .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                        .withBean(DataSource.class, () -> dataSource)
                        .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                        .withPropertyValues(
                                "seedj.iam.issuer=https://iam.test",
                                "seedj.iam.store=jdbc",
                                "seedj.iam.jwk.provider=jdbc",
                                "seedj.iam.demo.enabled=true",
                                "seedj.iam.secret.key=" + key,
                                "seedj.pki.enabled=false",
                                "seedj.transit.enabled=false",
                                "seedj.eventbus.enabled=false",
                                "seedj.outbox.enabled=false",
                                "seedj.id.enabled=false",
                                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    IamUserRepository users = context.getBean(IamUserRepository.class);
                    users.create(
                            IamUser.create(
                                    "u-persisted", "default", "persisted", "Persisted", null, true),
                            "{bcrypt}hash");
                    RegisteredClientRepository clients =
                            context.getBean(RegisteredClientRepository.class);
                    RegisteredClient client = clients.findByClientId("default-client");
                    assertThat(client).isNotNull();
                    OAuth2AuthorizationService authorizations =
                            context.getBean(OAuth2AuthorizationService.class);
                    OAuth2Authorization authorization =
                            OAuth2Authorization.withRegisteredClient(client)
                                    .principalName("persisted")
                                    .authorizationGrantType(
                                            org.springframework.security.oauth2.core
                                                    .AuthorizationGrantType.AUTHORIZATION_CODE)
                                    .authorizedScopes(Set.of("openid"))
                                    .build();
                    authorizations.save(authorization);
                    assertThat(authorizations.findById(authorization.getId())).isNotNull();
                    assertThat(context.getBean(IamSigningKeyStore.class).activeKey().kid())
                            .isNotBlank();
                });

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(
                                    context.getBean(IamUserRepository.class)
                                            .findByUserName("default", "persisted"))
                            .isPresent();
                    assertThat(
                                    context.getBean(RegisteredClientRepository.class)
                                            .findByClientId("default-client"))
                            .isNotNull();
                    assertThat(
                                    context.getBean(OAuth2AuthorizationService.class)
                                            .findByToken("missing", null))
                            .isNull();
                });
    }

    @Test
    void jdbcAuthorizationServiceReadsLegacyImmutableCollectionClaims() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        WebApplicationContextRunner runner =
                new WebApplicationContextRunner()
                        .withUserConfiguration(TestApplication.class)
                        .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                        .withBean(DataSource.class, () -> dataSource)
                        .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                        .withPropertyValues(
                                "seedj.iam.issuer=https://iam.test",
                                "seedj.iam.store=jdbc",
                                "seedj.iam.jwk.provider=jdbc",
                                "seedj.iam.demo.enabled=true",
                                "seedj.iam.secret.key=" + key,
                                "seedj.pki.enabled=false",
                                "seedj.transit.enabled=false",
                                "seedj.eventbus.enabled=false",
                                "seedj.outbox.enabled=false",
                                "seedj.id.enabled=false",
                                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    RegisteredClient client =
                            context.getBean(RegisteredClientRepository.class)
                                    .findByClientId("default-client");
                    Instant issuedAt = Instant.now();
                    // 升级前的行：ID token claims 与 attributes 里都是 List.of/List.copyOf 产生的
                    // java.util.ImmutableCollections$List12。SAS 把 ID token claims 写进
                    // oidc_id_token_metadata，刷新时按 refresh_token 读整行。
                    Map<String, Object> claims = new java.util.LinkedHashMap<>();
                    claims.put("iss", "https://iam.test");
                    claims.put("sub", "u-legacy");
                    claims.put("aud", List.copyOf(List.of("web")));
                    claims.put("roles", List.copyOf(List.of("reader", "writer")));
                    claims.put("groups", List.copyOf(List.of("sales")));
                    claims.put("amr", List.copyOf(List.of("pwd", "totp")));
                    OidcIdToken idToken =
                            new OidcIdToken(
                                    "id-legacy", issuedAt, issuedAt.plusSeconds(900), claims);
                    OAuth2Authorization.Builder builder =
                            OAuth2Authorization.withRegisteredClient(client)
                                    .principalName("legacy")
                                    .authorizationGrantType(
                                            org.springframework.security.oauth2.core
                                                    .AuthorizationGrantType.AUTHORIZATION_CODE)
                                    .accessToken(
                                            new OAuth2AccessToken(
                                                    OAuth2AccessToken.TokenType.BEARER,
                                                    "access-legacy",
                                                    issuedAt,
                                                    issuedAt.plusSeconds(900),
                                                    Set.of("openid")))
                                    .refreshToken(
                                            new OAuth2RefreshToken("refresh-legacy", issuedAt))
                                    .attribute("amr", List.of("pwd", "totp"))
                                    .attribute("device_id", "device-1");
                    builder.token(
                            idToken,
                            metadata ->
                                    metadata.put(
                                            OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                                            idToken.getClaims()));
                    OAuth2AuthorizationService authorizations =
                            context.getBean(OAuth2AuthorizationService.class);
                    authorizations.save(builder.build());

                    // 连续刷新：每次 findByToken 都要反序列化整行，再把同一行写回
                    for (int round = 0; round < 3; round++) {
                        OAuth2Authorization loaded =
                                authorizations.findByToken(
                                        "refresh-legacy", OAuth2TokenType.REFRESH_TOKEN);
                        assertThat(loaded).isNotNull();
                        OAuth2Authorization.Token<OidcIdToken> loadedIdToken =
                                loaded.getToken(OidcIdToken.class);
                        assertThat(loadedIdToken.getClaims())
                                .containsEntry("roles", List.of("reader", "writer"))
                                .containsEntry("amr", List.of("pwd", "totp"));
                        assertThat((Object) loadedIdToken.getClaims().get("roles"))
                                .isExactlyInstanceOf(java.util.ArrayList.class);
                        assertThat(loaded.<List<String>>getAttribute("amr"))
                                .containsExactly("pwd", "totp");
                        authorizations.save(loaded);
                    }
                });
    }

    @EnableIamServer
    static class TestApplication {

        @Bean
        IdGenerator<String> idGenerator() {
            return TestIdGenerators.sequential();
        }
    }
}
