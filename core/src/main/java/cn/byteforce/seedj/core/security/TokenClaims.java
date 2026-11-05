package cn.byteforce.seedj.core.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 与框架无关的令牌声明视图。
 *
 * <p>存在的理由：资源服务器（biz）与认证服务器（iam）必须用<b>同一套规则</b>
 * 校验访问令牌，但两侧的技术栈不同（Spring Security 的 {@code Jwt}、SAS 的
 * 内部表示）。把声明抽成 record 后，规则只有一份（{@link AccessTokenRules}），
 * 两侧各写一个 10 行的映射适配器。</p>
 *
 * @param subject   令牌主体（OAuth {@code sub}）
 * @param type      头部 {@code typ}；RFC 9068 访问令牌为 {@code at+jwt}
 * @param issuer    {@code iss}
 * @param expiresAt {@code exp}；{@code null} 表示声明缺失
 * @param audience  {@code aud}
 * @param attributes 其余声明，供 ABAC 使用
 * @author ByteForce
 * @since 1.0.0
 */
public record TokenClaims(
        String subject,
        String type,
        String issuer,
        Instant expiresAt,
        List<String> audience,
        Map<String, Object> attributes) {

    public TokenClaims {
        subject = subject == null ? "" : subject;
        type = type == null ? "" : type;
        issuer = issuer == null ? "" : issuer;
        audience = audience == null ? List.of() : List.copyOf(audience);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
