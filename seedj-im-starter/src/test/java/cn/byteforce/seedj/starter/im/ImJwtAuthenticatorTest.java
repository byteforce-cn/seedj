package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 访问令牌 → {@link Caller} 的握手映射与拒绝路径。 */
class ImJwtAuthenticatorTest {

    private static final SecretKey KEY = key("seedj-im-test-key-0123456789abcdef");
    private static final SecretKey OTHER_KEY = key("another-key-0123456789abcdefghij");

    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(decoder(KEY), new JwtClaimsMapper());

    @Test
    @DisplayName("有效令牌映射为 Caller")
    void validTokenYieldsCaller() {
        String token = token(KEY, "alice", "u-1", Instant.now().plusSeconds(300));

        Caller caller = authenticator.authenticate(token);

        assertThat(caller.username()).isEqualTo("alice");
        assertThat(caller.id()).isEqualTo("u-1");
        assertThat(caller.permissions()).contains("im.message.send");
    }

    @Test
    @DisplayName("空白令牌被拒绝")
    void blankTokenIsRejected() {
        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authenticator.authenticate("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("过期令牌被拒绝")
    void expiredTokenIsRejected() {
        String token = token(KEY, "alice", "u-1", Instant.now().minusSeconds(300));

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("错误密钥签发的令牌被拒绝")
    void wrongKeyIsRejected() {
        String token = token(OTHER_KEY, "alice", "u-1", Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> authenticator.authenticate(token))
                .isInstanceOf(JwtException.class);
    }

    private static JwtDecoder decoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static String token(SecretKey key, String subject, String userId, Instant expiresAt) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant issuedAt =
                expiresAt.isAfter(Instant.now())
                        ? Instant.now().minusSeconds(5)
                        : expiresAt.minusSeconds(300);
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(subject)
                        .claim("user_id", userId)
                        .claim("permissions", List.of("im.message.send"))
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static SecretKey key(String value) {
        return new SecretKeySpec(value.getBytes(UTF_8), "HmacSHA256");
    }
}
