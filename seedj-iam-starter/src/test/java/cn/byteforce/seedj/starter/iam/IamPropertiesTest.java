package cn.byteforce.seedj.starter.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 配置归一化与 TOTP 取值范围：绑定层仍是启动期唯一的校验点。 */
class IamPropertiesTest {

    @Test
    @DisplayName("缺省值：enabled、tenant、store 与嵌套记录都被归一化")
    void defaultsAreNormalized() {
        IamProperties properties = IamProperties.defaults();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.tenantId()).isEqualTo("default");
        assertThat(properties.store()).isEqualTo(IamProperties.Store.MEMORY);
        assertThat(properties.mfa().totp().issuer()).isEqualTo("Seedj");
        assertThat(properties.mfa().totp().digits()).isEqualTo(6);
        assertThat(properties.nativeAuth().maxAttempts()).isEqualTo(5);
        assertThat(properties.scim().basePath()).isEqualTo("/scim/v2");
        assertThat(properties.rbac().cacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.jwk().retireAfter()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.secret().keyName()).isEqualTo("iam-aes");
        assertThat(properties.clients()).isEmpty();
    }

    @Test
    @DisplayName("TOTP 的 digits/period/window 越界在绑定期直接失败")
    void totpRejectsOutOfRangeValues() {
        assertThatThrownBy(() -> totp(5, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digits");
        assertThatThrownBy(() -> totp(11, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digits");
        assertThatThrownBy(() -> totp(6, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> totp(6, Duration.ofSeconds(-1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period");
        assertThatThrownBy(() -> totp(6, Duration.ofSeconds(30), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> totp(6, Duration.ofSeconds(30), 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    @DisplayName("客户端与 MFA 的缺省值保持安全默认")
    void clientAndMfaDefaultsAreSafe() {
        IamProperties.Client client =
                new IamProperties.Client("web", "secret", null, null, null, null, null, null, null);

        assertThat(client.scopes()).containsExactly("openid", "profile");
        assertThat(client.redirectUris()).isEmpty();
        assertThat(client.grantTypes()).containsExactly("authorization_code", "refresh_token");
        assertThat(client.requireProofKey()).isTrue();
        assertThat(client.requireConsent()).isTrue();
        assertThat(client.authenticationMethod()).isEqualTo("client_secret_basic");
        assertThat(
                        new IamProperties.Client(
                                        "web", "s", null, List.of(), null, null, null, " ", null)
                                .authenticationMethod())
                .isEqualTo("client_secret_basic");
        assertThat(new IamProperties.Mfa(null, null).required()).isFalse();
        assertThat(new IamProperties.Scim(null, " ").basePath()).isEqualTo("/scim/v2");
    }

    private static IamProperties.Totp totp(int digits, Duration period, int window) {
        return new IamProperties.Totp(true, "Seedj", digits, period, window, 3, null);
    }
}
