package cn.byteforce.seedj.core.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 访问令牌规则：biz（资源服务器）与 iam（认证服务器）共用的唯一一份实现。
 *
 * <p>为什么放在 {@code core} 而不是某个 starter：两侧使用同一个令牌，规则必须 逐位一致；一旦各写一套，"认证服务器放行的令牌资源服务器拒绝"这类问题就无法
 * 通过构造保证。这里只有纯函数，不依赖 Spring、不读时钟、不抛异常。
 *
 * <p>RFC 9068 要点：访问令牌 {@code typ=at+jwt}、必须有 issuer、必须有非空 {@code aud}，因此 ID Token（{@code
 * typ=JWT}）无法当作访问令牌使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AccessTokenRules {

    /** RFC 9068 访问令牌类型。 */
    public static final String ACCESS_TOKEN_TYPE = "at+jwt";

    /** 默认允许的时钟偏移。 */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

    public static final String SCOPE_CLAIM = "scope";
    public static final String ROLES_CLAIM = "roles";
    public static final String PERMISSIONS_CLAIM = "permissions";
    public static final String GROUPS_CLAIM = "groups";
    public static final String AMR_CLAIM = "amr";
    public static final String ACR_CLAIM = "acr";
    public static final String USER_ID_CLAIM = "user_id";

    public static final String SCOPE_PREFIX = "SCOPE_";
    public static final String ROLE_PREFIX = "ROLE_";
    public static final String PERMISSION_PREFIX = "PERM_";
    public static final String GROUP_PREFIX = "GROUP_";

    private AccessTokenRules() {}

    /**
     * 校验访问令牌；通过返回空，否则返回第一个失败原因。
     *
     * @param claims 令牌声明
     * @param expectedIssuer 期望的 {@code iss}，不得空白
     * @param now 当前时间（由调用方注入 {@code Clock}）
     * @return 失败原因；成功为空
     * @throws IllegalArgumentException 期望 issuer 空白
     */
    public static Optional<SecurityErrorCode> check(
            TokenClaims claims, String expectedIssuer, Instant now) {
        if (claims == null) {
            return Optional.of(SecurityErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (expectedIssuer == null || expectedIssuer.isBlank()) {
            throw new IllegalArgumentException("expectedIssuer must not be blank");
        }
        if (!ACCESS_TOKEN_TYPE.equals(claims.type())) {
            return Optional.of(SecurityErrorCode.AUTHENTICATION_FAILED);
        }
        if (!expectedIssuer.equals(claims.issuer())) {
            return Optional.of(SecurityErrorCode.AUTHENTICATION_FAILED);
        }
        if (claims.expiresAt() == null) {
            return Optional.of(SecurityErrorCode.AUTHENTICATION_FAILED);
        }
        if (!claims.expiresAt().isAfter(now.minus(DEFAULT_CLOCK_SKEW))) {
            return Optional.of(SecurityErrorCode.TOKEN_EXPIRED);
        }
        if (claims.audience().isEmpty()) {
            return Optional.of(SecurityErrorCode.AUTHENTICATION_FAILED);
        }
        return Optional.empty();
    }

    /** 是否包含非空受众；供 Spring 的 {@code JwtClaimValidator} 复用。 */
    public static boolean hasAudience(Object value) {
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return false;
    }

    /**
     * 令牌声明 → Spring 风格权限串（{@code SCOPE_/ROLE_/PERM_/GROUP_} 前缀）。
     *
     * <p>顺序稳定：scope、roles、permissions、groups，各自内部保持令牌顺序。
     */
    public static List<String> authorities(TokenClaims claims) {
        List<String> result = new ArrayList<>();
        for (String scope : stringValues(claims.attributes().get(SCOPE_CLAIM))) {
            result.add(SCOPE_PREFIX + scope);
        }
        addPrefixed(result, claims.attributes().get(ROLES_CLAIM), ROLE_PREFIX);
        addPrefixed(result, claims.attributes().get(PERMISSIONS_CLAIM), PERMISSION_PREFIX);
        addPrefixed(result, claims.attributes().get(GROUPS_CLAIM), GROUP_PREFIX);
        return List.copyOf(result);
    }

    /**
     * 令牌声明 → 调用方视图。
     *
     * @param claims 已通过 {@link #check} 的声明
     * @param tenantId 租户；空白回落 {@link Caller#DEFAULT_TENANT}
     */
    public static Caller toCaller(TokenClaims claims, String tenantId) {
        Map<String, Object> attributes = new LinkedHashMap<>(claims.attributes());
        String userId = stringValue(attributes.get(USER_ID_CLAIM));
        String acr = stringValue(attributes.get(ACR_CLAIM));
        Set<String> amr = new LinkedHashSet<>(stringValues(attributes.get(AMR_CLAIM)));
        return new Caller(
                userId.isBlank() ? claims.subject() : userId,
                claims.subject(),
                tenantId,
                new LinkedHashSet<>(stringValues(attributes.get(ROLES_CLAIM))),
                new LinkedHashSet<>(stringValues(attributes.get(PERMISSIONS_CLAIM))),
                new LinkedHashSet<>(stringValues(attributes.get(GROUPS_CLAIM))),
                amr,
                acr,
                attributes);
    }

    private static void addPrefixed(List<String> target, Object raw, String prefix) {
        for (String value : stringValues(raw)) {
            target.add(prefix + value);
        }
    }

    /** 把声明值读成字符串列表：集合逐项转字符串，字符串按空白切分 （OAuth2 的 {@code scope} 常为 {@code "read write"}）。 */
    static List<String> stringValues(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object item : collection) {
                String value = stringValue(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
        String text = stringValue(raw);
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(text.trim().split("\\s+"));
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
