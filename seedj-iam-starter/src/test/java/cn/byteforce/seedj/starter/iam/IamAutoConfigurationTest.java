package cn.byteforce.seedj.starter.iam;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.spi.IamUserRepository;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.starter.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.starter.iam.identity.IamIdentityService;
import cn.byteforce.seedj.starter.iam.identity.IamTotpService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class IamAutoConfigurationTest {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(TestApplication.class)
                    .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                    .withPropertyValues(
                            "seedj.iam.issuer=https://iam.test",
                            "seedj.iam.store=memory",
                            "seedj.iam.demo.enabled=true",
                            "seedj.iam.native-auth.enabled=true",
                            "seedj.iam.secret.key="
                                    + java.util.Base64.getEncoder().encodeToString(new byte[32]),
                            "seedj.iam.admin.bootstrap-username=admin",
                            "seedj.iam.admin.bootstrap-password=admin-password",
                            "seedj.iam.clients[0].client-id=web",
                            "seedj.iam.clients[0].client-secret=web-secret",
                            "seedj.iam.clients[0].redirect-uris[0]=https://app.example.com/callback",
                            "seedj.iam.clients[0].scopes[0]=openid",
                            "seedj.iam.clients[0].grant-types[0]=authorization_code",
                            "seedj.pki.enabled=false",
                            "seedj.transit.enabled=false",
                            "seedj.eventbus.enabled=false",
                            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                            "seedj.id.enabled=false");

    @Test
    void startsWithMemoryStoreAndRegistersDemoClients() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IamUserRepository.class);
                    assertThat(context).hasSingleBean(IamIdentityService.class);
                    assertThat(context).hasSingleBean(IamTotpService.class);
                    assertThat(
                                    context.getBean(
                                            cn.byteforce.seedj.security.AuthorizationDecisionPoint
                                                    .class))
                            .isInstanceOf(
                                    cn.byteforce.seedj.starter.iam.abac
                                            .CompositeAuthorizationDecisionPoint.class);
                    RegisteredClientRepository clients =
                            context.getBean(RegisteredClientRepository.class);
                    assertThat(clients.findByClientId("default-client")).isNotNull();
                    assertThat(clients.findByClientId("native-app")).isNotNull();
                    assertThat(clients.findByClientId("web")).isNotNull();
                    assertThat(clients.findByClientId("web").getClientSecret())
                            .startsWith("{bcrypt}");
                    assertThat(
                                    context.getBean(IamUserRepository.class)
                                            .findByUserName("default", "admin"))
                            .isPresent();
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
