package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** 会话上限与会话溢出策略。 */
class ImSessionLimitTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private final ImCodec codec = new ImCodec();
    private final ChannelRegistry registry = new ChannelRegistry();
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("reject 策略：超限连接回 429 并关闭，既有连接不受影响")
    void rejectOverflowClosesNewConnection() {
        EmbeddedChannel first = channel(properties(1, ImProperties.SessionOverflow.REJECT));
        EmbeddedChannel second = channel(properties(1, ImProperties.SessionOverflow.REJECT));
        try {
            first.writeInbound(request(new LoginReq(token(), "dev-1"), 1));
            assertThat(first.<ImFrame>readOutbound()).isNotNull();

            second.writeInbound(request(new LoginReq(token(), "dev-2"), 2));

            ImFrame response = second.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            assertThat(second.isActive()).isFalse();
            assertThat(first.isActive()).isTrue();
            assertThat(registry.channels("u-1")).containsExactly(first);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("evict-oldest 策略：关掉最早连接，放行新连接")
    void evictOldestKeepsNewestConnection() {
        ImProperties properties = properties(1, ImProperties.SessionOverflow.EVICT_OLDEST);
        EmbeddedChannel first = channel(properties);
        EmbeddedChannel second = channel(properties);
        try {
            first.writeInbound(request(new LoginReq(token(), "dev-1"), 1));
            assertThat(first.<ImFrame>readOutbound()).isNotNull();

            second.writeInbound(request(new LoginReq(token(), "dev-2"), 2));

            ImFrame response = second.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.LOGIN_RESP);
            assertThat(codec.decode(response, LoginResp.class).code()).isZero();
            assertThat(second.isActive()).isTrue();
            assertThat(registry.channels("u-1")).containsExactly(second);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channel(ImProperties properties) {
        return new EmbeddedChannel(
                new ImHandshakeHandler(codec, authenticator, registry, properties),
                new ImDispatchHandler(codec));
    }

    private static ImProperties properties(int maxSessions, ImProperties.SessionOverflow overflow) {
        return new ImProperties(
                true,
                ImProperties.Store.MEMORY,
                new ImProperties.Push("local"),
                new ImProperties.Tcp(false, 0),
                new ImProperties.Ws(false, 0, "/ws"),
                null,
                0,
                maxSessions,
                overflow,
                null,
                null,
                null);
    }

    private ImFrame request(Object body, int sequenceId) {
        return codec.resp(Cmd.LOGIN_REQ, sequenceId, body);
    }

    private static String token() {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject("alice")
                        .claim("user_id", "u-1")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
