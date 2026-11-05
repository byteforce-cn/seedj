package cn.byteforce.seedj.iam;

import cn.byteforce.seedj.iam.annotation.EnableIamServer;
import cn.byteforce.seedj.iam.autoconfigure.IamAutoConfiguration;
import cn.byteforce.seedj.iam.identity.IamIdentityService;
import cn.byteforce.seedj.iam.identity.IamTotpService;
import cn.byteforce.seedj.iam.nativeauth.NativeAuthAuthenticationProvider;
import cn.byteforce.seedj.iam.rbac.IamRbacAdminController;
import cn.byteforce.seedj.iam.scim.ScimController;
import cn.byteforce.seedj.id.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

class IamSubCapabilitySwitchesTest {

    @Test
    void coreStartsWhenEveryOptionalCapabilityIsDisabled() {
        new WebApplicationContextRunner()
                .withUserConfiguration(TestApplication.class)
                .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.iam.issuer=https://iam.test",
                        "seedj.iam.store=memory",
                        "seedj.iam.demo.enabled=false",
                        "seedj.iam.scim.enabled=false",
                        "seedj.iam.mfa.totp.enabled=false",
                        "seedj.iam.native-auth.enabled=false",
                        "seedj.iam.rbac.enabled=false",
                        "seedj.iam.abac.enabled=false",
                        "seedj.iam.secret.key=" + java.util.Base64.getEncoder()
                                .encodeToString(new byte[32]),
                        "seedj.pki.enabled=false",
                        "seedj.transit.enabled=false",
                        "seedj.eventbus.enabled=false",
                        "seedj.id.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IamIdentityService.class);
                    assertThat(context).doesNotHaveBean(IamTotpService.class);
                    assertThat(context).doesNotHaveBean(NativeAuthAuthenticationProvider.class);
                    assertThat(context).doesNotHaveBean(ScimController.class);
                    assertThat(context).doesNotHaveBean(IamRbacAdminController.class);
                });
    }

    @Test
    void jdbcStoreFailsFastWithoutDataSource() {
        new WebApplicationContextRunner()
                .withUserConfiguration(TestApplication.class)
                .withConfiguration(AutoConfigurations.of(IamAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.iam.issuer=https://iam.test",
                        "seedj.iam.store=jdbc",
                        "seedj.iam.secret.key=" + java.util.Base64.getEncoder()
                                .encodeToString(new byte[32]),
                        "seedj.pki.enabled=false",
                        "seedj.transit.enabled=false",
                        "seedj.eventbus.enabled=false",
                        "seedj.outbox.enabled=false",
                        "seedj.id.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("seedj.iam.store=jdbc requires a DataSource"));
    }

    @EnableIamServer
    static class TestApplication {

        @Bean
        IdGenerator<String> idGenerator() {
            return TestIdGenerators.sequential();
        }
    }
}
