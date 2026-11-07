package cn.byteforce.seedj.iam.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpGeneratorTest {

    private static final byte[] SHA1_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA256_SECRET =
            "12345678901234567890123456789012".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHA512_SECRET =
            ("1234567890123456789012345678901234567890123456789012345678901234")
                    .getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
        "59,94287082",
        "1111111109,07081804",
        "1111111111,14050471",
        "1234567890,89005924",
        "2000000000,69279037",
        "20000000000,65353130"
    })
    void matchesRfc6238Sha1Vectors(long epochSecond, String expected) {
        assertThat(TotpGenerator.generate(SHA1_SECRET, epochSecond, 8, 30, TotpAlgorithm.SHA1))
                .isEqualTo(expected);
    }

    @Test
    void matchesRfc6238Sha256AndSha512Vectors() {
        assertThat(TotpGenerator.generate(SHA256_SECRET, 59, 8, 30, TotpAlgorithm.SHA256))
                .isEqualTo("46119246");
        assertThat(TotpGenerator.generate(SHA512_SECRET, 59, 8, 30, TotpAlgorithm.SHA512))
                .isEqualTo("90693936");
    }

    @Test
    void acceptsOnlyOneStepOfClockSkewAndRejectsReplay() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code =
                TotpGenerator.generate(
                        SHA1_SECRET, now.minusSeconds(30), 6, 30, TotpAlgorithm.SHA1);

        TotpGenerator.Result result =
                TotpGenerator.verify(SHA1_SECRET, code, now, 1, 6, 30, TotpAlgorithm.SHA1, -1);

        assertThat(result.valid()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(
                        TotpGenerator.verify(
                                        SHA1_SECRET,
                                        code,
                                        now,
                                        1,
                                        6,
                                        30,
                                        TotpAlgorithm.SHA1,
                                        result.step())
                                .replayed())
                .isTrue();
    }

    @Test
    void rejectsMalformedCodes() {
        assertThat(
                        TotpGenerator.verify(
                                        SHA1_SECRET,
                                        "12345",
                                        Instant.now(),
                                        1,
                                        6,
                                        30,
                                        TotpAlgorithm.SHA1,
                                        -1)
                                .valid())
                .isFalse();
        assertThat(
                        TotpGenerator.verify(
                                        SHA1_SECRET,
                                        "abcdef",
                                        Instant.now(),
                                        1,
                                        6,
                                        30,
                                        TotpAlgorithm.SHA1,
                                        -1)
                                .valid())
                .isFalse();
    }

    @Test
    void base32RoundTripsAndBuildsOtpauthUri() {
        byte[] secret = SHA1_SECRET;
        String encoded = Base32.encode(secret);
        assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Base32.decode(encoded)).isEqualTo(secret);

        String uri =
                TotpGenerator.otpauthUri(
                        "Seedj", "alice@example.com", encoded, 6, 30, TotpAlgorithm.SHA1);
        assertThat(uri)
                .startsWith("otpauth://totp/Seedj:alice%40example.com?")
                .contains(
                        "secret=" + encoded,
                        "issuer=Seedj",
                        "algorithm=SHA1",
                        "digits=6",
                        "period=30");
    }
}
