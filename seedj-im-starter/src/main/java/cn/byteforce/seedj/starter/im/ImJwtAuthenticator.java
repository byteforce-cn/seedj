package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.TokenClaims;
import cn.byteforce.seedj.starter.security.JwtClaimsMapper;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 用 IAM 签发的访问令牌完成握手：验签、映射声明、得到 {@link Caller}。
 *
 * <p>Netty 线程上没有过滤器链，这里直接复用资源服务器提供的 {@link JwtDecoder}。
 */
final class ImJwtAuthenticator {

    private final JwtDecoder decoder;
    private final JwtClaimsMapper mapper;

    ImJwtAuthenticator(JwtDecoder decoder, JwtClaimsMapper mapper) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 校验访问令牌并映射为调用方。
     *
     * @param accessToken IAM 签发的令牌
     * @return 调用方快照
     * @throws IllegalArgumentException 令牌为空白
     * @throws JwtException 令牌无效、过期或签名不符
     */
    Caller authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("access_token 不能为空");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(accessToken);
        } catch (JwtException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new JwtException("访问令牌校验失败", failure);
        }
        TokenClaims claims = mapper.map(jwt);
        return AccessTokenRules.toCaller(claims, Caller.DEFAULT_TENANT);
    }
}
