package cn.byteforce.seedj.starter.im;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.starter.im.protocol.Cmd;
import cn.byteforce.seedj.starter.im.protocol.ImFrame;
import cn.byteforce.seedj.starter.im.protocol.body.ErrorResp;
import cn.byteforce.seedj.starter.im.protocol.body.LoginReq;
import cn.byteforce.seedj.starter.im.protocol.body.LoginResp;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Instant;
import java.util.List;
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

/** 登录握手、心跳与未实现命令的分发语义。 */
class ImDispatchHandlerTest {

    private static final SecretKey KEY =
            new SecretKeySpec("seedj-im-test-key-0123456789abcdef".getBytes(UTF_8), "HmacSHA256");

    private final ImCodec codec = new ImCodec();
    private final ChannelRegistry registry = new ChannelRegistry();
    private final ImJwtAuthenticator authenticator =
            new ImJwtAuthenticator(
                    NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build(),
                    new JwtClaimsMapper());

    @Test
    @DisplayName("有效令牌登录后绑定连接并回 LOGIN_RESP")
    void loginWithValidTokenBindsChannel() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(loginRequest(validToken(), 7));

            ImFrame response = channel.readOutbound();
            assertThat(response).isNotNull();
            assertThat(response.cmd()).isEqualTo(Cmd.LOGIN_RESP);
            LoginResp body = codec.decode(response, LoginResp.class);
            assertThat(body.code()).isZero();
            assertThat(body.userId()).isEqualTo("u-1");
            assertThat(registry.channels("u-1")).containsExactly(channel);
            assertThat(channel.isActive()).isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("坏令牌回 401 并关闭连接")
    void loginWithBadTokenReplies401AndCloses() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(request(Cmd.LOGIN_REQ, new LoginReq("not-a-jwt"), 8));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            ErrorResp error = codec.decode(response, ErrorResp.class);
            assertThat(error.code()).isEqualTo(401);
            assertThat(error.message()).isEqualTo("认证失败");
            assertThat(channel.isActive()).isFalse();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("缺失 access_token 回 401 且注册表为空")
    void missingAccessTokenReplies401AndLeavesRegistryEmpty() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(request(Cmd.LOGIN_REQ, new LoginReq("  "), 9));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            ErrorResp error = codec.decode(response, ErrorResp.class);
            assertThat(error.code()).isEqualTo(401);
            assertThat(error.message()).isEqualTo("认证失败");
            assertThat(channel.isActive()).isFalse();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("过期令牌回 401 且不泄露校验细节")
    void expiredTokenReplies401AndLeavesRegistryEmpty() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(
                    request(
                            Cmd.LOGIN_REQ,
                            new LoginReq(token("alice", "u-1", Instant.now().minusSeconds(300))),
                            14));

            ImFrame response = channel.readOutbound();
            ErrorResp error = codec.decode(response, ErrorResp.class);
            assertThat(error.code()).isEqualTo(401);
            assertThat(error.message()).isEqualTo("认证失败");
            assertThat(channel.isActive()).isFalse();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("同一连接重新登录会移除旧主体的注册")
    void reLoginMovesRegistryEntryToNewCaller() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(loginRequest(validToken(), 15));
            assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);

            channel.writeInbound(
                    loginRequest(token("bob", "u-2", Instant.now().plusSeconds(300)), 16));
            assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);

            assertThat(registry.channels("u-1")).isEmpty();
            assertThat(registry.channels("u-2")).containsExactly(channel);
            channel.close();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("断开一端时同一用户的其他连接仍在")
    void channelInactiveKeepsOtherChannelForSameSubject() {
        EmbeddedChannel first = channel();
        EmbeddedChannel second = channel();
        try {
            first.writeInbound(loginRequest(validToken(), 17));
            first.<ImFrame>readOutbound();
            second.writeInbound(loginRequest(validToken(), 18));
            second.<ImFrame>readOutbound();

            first.close();

            assertThat(registry.channels("u-1")).containsExactly(second);
            second.close();
            assertThat(registry.size()).isZero();
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("登录后心跳 PING 回 PONG")
    void pingRepliesPong() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(loginRequest(validToken(), 10));
            assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);

            channel.writeInbound(request(Cmd.HEARTBEAT_PING, null, 10));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.HEARTBEAT_PONG);
            assertThat(response.sequenceId()).isEqualTo(10);
            assertThat(channel.isActive()).isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("未登录发业务帧回 401 并关闭")
    void businessCommandWithoutLoginReplies401() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(request(Cmd.CHAT_SINGLE_REQ, null, 11));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            assertThat(codec.decode(response, ErrorResp.class).code()).isEqualTo(401);
            assertThat(channel.isActive()).isFalse();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("已登录发未实现命令回 501 且连接保持")
    void unimplementedCommandReplies501() {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(loginRequest(validToken(), 12));
            assertThat(channel.<ImFrame>readOutbound().cmd()).isEqualTo(Cmd.LOGIN_RESP);

            channel.writeInbound(request(Cmd.CHAT_SINGLE_REQ, null, 13));

            ImFrame response = channel.readOutbound();
            assertThat(response.cmd()).isEqualTo(Cmd.ERROR);
            ErrorResp error = codec.decode(response, ErrorResp.class);
            assertThat(error.code()).isEqualTo(501);
            assertThat(error.message()).contains("未实现");
            assertThat(channel.isActive()).isTrue();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(
                new ImHandshakeHandler(codec, authenticator, registry),
                new ImDispatchHandler(codec));
    }

    private ImFrame loginRequest(String token, int sequenceId) {
        return request(Cmd.LOGIN_REQ, new LoginReq(token), sequenceId);
    }

    private ImFrame request(byte cmd, Object body, int sequenceId) {
        return codec.resp(cmd, sequenceId, body);
    }

    private static String validToken() {
        return token("alice", "u-1", Instant.now().plusSeconds(300));
    }

    private static String token(String subject, String userId, Instant expiresAt) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
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
}
