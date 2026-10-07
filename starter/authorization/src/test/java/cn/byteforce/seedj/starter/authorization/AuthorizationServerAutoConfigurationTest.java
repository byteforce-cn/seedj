package cn.byteforce.seedj.starter.authorization;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthorizationServerAutoConfiguration} 装配与开关契约测试。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class AuthorizationServerAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuthorizationServerAutoConfiguration.class));

    @Test
    void registersClaimsCustomizerByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SeedjJwtClaimsCustomizer.class);
            assertThat(context).doesNotHaveBean(AuthorizationServerSettings.class);
        });
    }

    @Test
    void issuerPropertyRegistersSettingsBean() {
        runner.withPropertyValues("byteforce.security.authorization.issuer=https://auth.example.com")
                .run(context -> {
                    AuthorizationServerSettings settings = context.getBean(AuthorizationServerSettings.class);
                    assertThat(settings.getIssuer()).isEqualTo("https://auth.example.com");
                });
    }

    @Test
    void killSwitchDisablesEverything() {
        runner.withPropertyValues("byteforce.security.authorization.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SeedjJwtClaimsCustomizer.class));
    }
}
