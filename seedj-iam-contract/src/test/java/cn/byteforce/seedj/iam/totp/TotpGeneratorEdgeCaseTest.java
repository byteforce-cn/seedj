package cn.byteforce.seedj.iam.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TOTP/Base32 边界：空输入、非法字符、无匹配验证码与负 step 跳过。 */
class TotpGeneratorEdgeCaseTest {

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("Base32：空输入编码为空串，解码为空数组")
    void base32EmptyInput() {
        assertThat(Base32.encode(new byte[0])).isEmpty();
        assertThat(Base32.decode("")).isEmpty();
    }

    @Test
    @DisplayName("Base32：无填充，尾部按位补齐，并容忍填充与短横线")
    void base32PadsTrailingBits() {
        assertThat(Base32.encode("a".getBytes(StandardCharsets.US_ASCII))).isEqualTo("ME");
        assertThat(Base32.decode("ME")).isEqualTo("a".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("me======")).isEqualTo("a".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base32.decode("gezd-gnbv-gy3t-qojq-gezd-gnbv-gy3t-qojq")).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("Base32：非法字符（含非 ASCII）被拒绝")
    void base32RejectsInvalidCharacters() {
        assertThatThrownBy(() -> Base32.decode("M1======"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid Base32 character");
        assertThatThrownBy(() -> Base32.decode("中======="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("verify：null/空验证码直接无效")
    void verifyRejectsNullOrEmptyCode() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        assertThat(
                        TotpGenerator.verify(SECRET, null, now, 1, 6, 30, TotpAlgorithm.SHA1, -1)
                                .valid())
                .isFalse();
        assertThat(
                        TotpGenerator.verify(SECRET, "  ", now, 1, 6, 30, TotpAlgorithm.SHA1, -1)
                                .valid())
                .isFalse();
        assertThat(
                        TotpGenerator.verify(
                                        SECRET, "123456", now, 1, 6, 30, TotpAlgorithm.SHA1, -1)
                                .valid())
                .isFalse();
    }

    @Test
    @DisplayName("verify：验证码中的空格被忽略")
    void verifyIgnoresSpaces() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = TotpGenerator.generate(SECRET, now, 6, 30, TotpAlgorithm.SHA1);
        String spaced = code.substring(0, 3) + " " + code.substring(3);

        TotpGenerator.Result result =
                TotpGenerator.verify(SECRET, spaced, now, 0, 6, 30, TotpAlgorithm.SHA1, -1);

        assertThat(result.valid()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.step()).isEqualTo(Math.floorDiv(now.getEpochSecond(), 30));
    }

    @Test
    @DisplayName("verify：窗口跨过 epoch 时跳过负 step")
    void verifySkipsNegativeSteps() {
        Instant now = Instant.ofEpochSecond(10);
        String code = TotpGenerator.generate(SECRET, now, 6, 30, TotpAlgorithm.SHA1);

        TotpGenerator.Result result =
                TotpGenerator.verify(SECRET, code, now, 5, 6, 30, TotpAlgorithm.SHA1, -1);

        assertThat(result.valid()).isTrue();
        assertThat(result.step()).isZero();
    }

    @Test
    @DisplayName("otpauth URI：账号与 issuer 做 URL 编码")
    void otpauthUriEncodesParts() {
        String uri =
                TotpGenerator.otpauthUri(
                        "Seedj Cloud",
                        "alice+ops@example.com",
                        "GEZDGNBVGY3TQOJQ",
                        8,
                        60,
                        TotpAlgorithm.SHA256);

        assertThat(uri)
                .isEqualTo(
                        "otpauth://totp/Seedj%20Cloud:alice%2Bops%40example.com"
                                + "?secret=GEZDGNBVGY3TQOJQ&issuer=Seedj%20Cloud&algorithm=SHA256"
                                + "&digits=8&period=60");
    }

    @Test
    @DisplayName("TotpEnrollment：注册材料同时给出 secret 与 otpauth URI")
    void totpEnrollment() {
        TotpEnrollment enrollment =
                new TotpEnrollment("GEZDGNBVGY3TQOJQ", "otpauth://totp/Seedj:alice");

        assertThat(enrollment.secret()).isEqualTo("GEZDGNBVGY3TQOJQ");
        assertThat(enrollment.otpauthUri()).isEqualTo("otpauth://totp/Seedj:alice");
    }
}
