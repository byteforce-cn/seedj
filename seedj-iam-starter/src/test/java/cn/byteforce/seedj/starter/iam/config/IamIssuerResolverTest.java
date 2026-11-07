package cn.byteforce.seedj.starter.iam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.starter.iam.IamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class IamIssuerResolverTest {

    @Test
    void prefersTheIamIssuerAndStripsTrailingSlash() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty(
                                "spring.security.oauth2.authorizationserver.issuer",
                                "https://boot.test");

        IamIssuerResolver resolver =
                new IamIssuerResolver(environment, properties("https://iam.test/"));

        assertThat(resolver.issuer()).isEqualTo("https://iam.test");
    }

    @Test
    void fallsBackToTheBootStandardProperty() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty(
                                "spring.security.oauth2.authorizationserver.issuer",
                                "https://boot.test/");

        assertThat(new IamIssuerResolver(environment, properties(null)).issuer())
                .isEqualTo("https://boot.test");
        assertThat(new IamIssuerResolver(environment, properties("  ")).issuer())
                .isEqualTo("https://boot.test");
    }

    @Test
    void failsWhenNoIssuerIsConfigured() {
        assertThatThrownBy(() -> new IamIssuerResolver(new MockEnvironment(), properties(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM issuer is not configured")
                .hasMessageContaining("seedj.iam.issuer");
    }

    private static IamProperties properties(String issuer) {
        return new IamProperties(
                null, issuer, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }
}
