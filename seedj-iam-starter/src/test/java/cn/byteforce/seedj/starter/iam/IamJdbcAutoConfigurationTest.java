package cn.byteforce.seedj.starter.iam;

import cn.byteforce.seedj.iam.IamUser;

import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class IamJdbcAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("iam").withUsername("iam").withPassword("iam");

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
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
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

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IamUserRepository users = context.getBean(IamUserRepository.class);
            users.create(IamUser.create("u-persisted", "default", "persisted", "Persisted", null, true),
                    "{bcrypt}hash");
            RegisteredClientRepository clients = context.getBean(RegisteredClientRepository.class);
            RegisteredClient client = clients.findByClientId("default-client");
            assertThat(client).isNotNull();
            OAuth2AuthorizationService authorizations =
                    context.getBean(OAuth2AuthorizationService.class);
            OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                    .principalName("persisted")
                    .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizedScopes(Set.of("openid"))
                    .build();
            authorizations.save(authorization);
            assertThat(authorizations.findById(authorization.getId())).isNotNull();
            assertThat(context.getBean(IamSigningKeyStore.class).activeKey().kid()).isNotBlank();
        });

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IamUserRepository.class)
                    .findByUserName("default", "persisted")).isPresent();
            assertThat(context.getBean(RegisteredClientRepository.class)
                    .findByClientId("default-client")).isNotNull();
            assertThat(context.getBean(OAuth2AuthorizationService.class)
                    .findByToken("missing", null)).isNull();
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
