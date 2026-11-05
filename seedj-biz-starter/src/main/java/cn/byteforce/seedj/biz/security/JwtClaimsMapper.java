package cn.byteforce.seedj.biz.security;

import cn.byteforce.seedj.core.security.AccessTokenRules;
import cn.byteforce.seedj.core.security.TokenClaims;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Spring {@link Jwt} → {@link TokenClaims} 映射。
 *
 * <p>两侧唯一的技术适配：规则本体在 {@link AccessTokenRules}，这里只做字段搬运，
 * 因此不包含任何判定逻辑。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class JwtClaimsMapper {

    /** Spring 把 {@code typ} 放在 header 里，不在 claims 里。 */
    static final String TYPE_HEADER = "typ";

    /** 映射为与框架无关的声明视图。 */
    public TokenClaims map(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt");
        // 注意：不能 put 值为 null 的 claim —— TokenClaims 用 Map.copyOf，null 值会抛 NPE。
        Map<String, Object> attributes = new LinkedHashMap<>(jwt.getClaims());
        return new TokenClaims(
                jwt.getSubject(),
                header(jwt),
                jwt.getClaimAsString("iss"),
                jwt.getExpiresAt(),
                jwt.getAudience(),
                attributes);
    }

    private static String header(Jwt jwt) {
        Object value = jwt.getHeaders().get(TYPE_HEADER);
        return value == null ? "" : String.valueOf(value);
    }
}
