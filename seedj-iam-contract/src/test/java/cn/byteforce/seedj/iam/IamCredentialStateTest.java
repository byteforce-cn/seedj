package cn.byteforce.seedj.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 认证状态模型：MFA challenge 生命周期、TOTP 加密状态与签名密钥轮换。 */
class IamCredentialStateTest {

    private static final Instant NOW = Instant.parse("2026-02-01T08:00:00Z");

    @Test
    @DisplayName("IamMfaChallenge：默认 5 次上限、5 分钟有效期，设备 ID 归一化")
    void mfaChallengeDefaults() {
        IamMfaChallenge challenge =
                new IamMfaChallenge("c-1", "u-1", "client-1", null, "sha256", null, false, -1, 0);

        assertThat(challenge.id()).isEqualTo("c-1");
        assertThat(challenge.userId()).isEqualTo("u-1");
        assertThat(challenge.clientId()).isEqualTo("client-1");
        assertThat(challenge.deviceId()).isEmpty();
        assertThat(challenge.tokenHash()).isEqualTo("sha256");
        assertThat(challenge.expiresAt()).isAfter(Instant.now());
        assertThat(challenge.attempts()).isZero();
        assertThat(challenge.maxAttempts()).isEqualTo(5);
        assertThat(challenge.consumed()).isFalse();
    }

    @Test
    @DisplayName("IamMfaChallenge：expired 以 now >= expiresAt 判定")
    void mfaChallengeExpiry() {
        IamMfaChallenge challenge =
                new IamMfaChallenge(
                        "c-1", "u-1", "client-1", "device-1", "sha256", NOW, false, 0, 3);

        assertThat(challenge.expired(NOW.minusSeconds(1))).isFalse();
        assertThat(challenge.expired(NOW)).isTrue();
        assertThat(challenge.expired(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("IamMfaChallenge：attempts 累加到达上限后 locked，consumed 独立")
    void mfaChallengeAttemptsAndLock() {
        IamMfaChallenge challenge =
                new IamMfaChallenge(
                        "c-1", "u-1", "client-1", "device-1", "sha256", NOW, false, 0, 2);

        assertThat(challenge.locked()).isFalse();
        IamMfaChallenge first = challenge.withAttempt();
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.locked()).isFalse();

        IamMfaChallenge second = first.withAttempt();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.locked()).isTrue();
        assertThat(second.expiresAt()).isEqualTo(NOW);
        assertThat(second.deviceId()).isEqualTo("device-1");

        IamMfaChallenge consumed = second.markConsumed();
        assertThat(consumed.consumed()).isTrue();
        assertThat(consumed.attempts()).isEqualTo(2);
        assertThat(consumed.maxAttempts()).isEqualTo(2);
        assertThat(consumed.id()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("IamMfaChallenge：id/userId/clientId/tokenHash 必填")
    void mfaChallengeValidation() {
        assertThatThrownBy(() -> new IamMfaChallenge(null, "u-1", "c", null, "h", NOW, false, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new IamMfaChallenge("c-1", " ", "c", null, "h", NOW, false, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> new IamMfaChallenge("c-1", "u-1", "", null, "h", NOW, false, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientId");
        assertThatThrownBy(
                        () -> new IamMfaChallenge("c-1", "u-1", "c", null, null, NOW, false, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenHash");
    }

    @Test
    @DisplayName("IamTotpState：lastUsedStep 下限 -1，确认与推进 step")
    void totpState() {
        IamTotpState state =
                new IamTotpState("u-1", null, "totp-key", "ciphertext", false, -10, null, null);

        assertThat(state.userId()).isEqualTo("u-1");
        assertThat(state.tenantId()).isEqualTo(IamUser.DEFAULT_TENANT);
        assertThat(state.keyName()).isEqualTo("totp-key");
        assertThat(state.secretCiphertext()).isEqualTo("ciphertext");
        assertThat(state.confirmed()).isFalse();
        assertThat(state.lastUsedStep()).isEqualTo(-1);
        assertThat(state.createdAt()).isNotNull();
        assertThat(state.confirmedAt()).isNull();

        IamTotpState confirmed = state.confirmed(NOW);
        assertThat(confirmed.confirmed()).isTrue();
        assertThat(confirmed.confirmedAt()).isEqualTo(NOW);
        assertThat(confirmed.lastUsedStep()).isEqualTo(-1);

        IamTotpState advanced = confirmed.withLastUsedStep(1_234L);
        assertThat(advanced.lastUsedStep()).isEqualTo(1_234L);
        assertThat(advanced.confirmed()).isTrue();
        assertThat(advanced.confirmedAt()).isEqualTo(NOW);
        assertThat(advanced.keyName()).isEqualTo("totp-key");

        assertThatThrownBy(() -> new IamTotpState(null, null, "k", "c", false, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> new IamTotpState("u-1", null, " ", "c", false, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyName");
        assertThatThrownBy(() -> new IamTotpState("u-1", null, "k", "", false, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secretCiphertext");
    }

    @Test
    @DisplayName("IamSigningKey：仅活跃且含私钥可签名，retire 清空私钥")
    void signingKey() {
        IamSigningKey key =
                new IamSigningKey("kid-1", null, "{\"kty\":\"RSA\"}", null, true, null, null);

        assertThat(key.kid()).isEqualTo("kid-1");
        assertThat(key.algorithm()).isEqualTo("RS256");
        assertThat(key.publicJwk()).isEqualTo("{\"kty\":\"RSA\"}");
        assertThat(key.privateJwk()).isEmpty();
        assertThat(key.active()).isTrue();
        assertThat(key.createdAt()).isNotNull();
        assertThat(key.retiredAt()).isNull();
        assertThat(key.canSign()).isFalse();

        IamSigningKey signable =
                new IamSigningKey("kid-2", "ES256", "{}", "{\"d\":\"x\"}", true, NOW, null);
        assertThat(signable.algorithm()).isEqualTo("ES256");
        assertThat(signable.canSign()).isTrue();

        IamSigningKey retired = signable.retire(NOW.plusSeconds(10));
        assertThat(retired.active()).isFalse();
        assertThat(retired.canSign()).isFalse();
        assertThat(retired.privateJwk()).isEmpty();
        assertThat(retired.publicJwk()).isEqualTo("{}");
        assertThat(retired.retiredAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(retired.createdAt()).isEqualTo(NOW);

        assertThat(
                        new IamSigningKey("kid-3", " ", "{}", "{\"d\":\"x\"}", false, NOW, null)
                                .canSign())
                .isFalse();

        assertThatThrownBy(() -> new IamSigningKey(null, null, "{}", null, true, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kid");
        assertThatThrownBy(() -> new IamSigningKey("kid-1", null, " ", null, true, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicJwk");
    }
}
