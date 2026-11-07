package cn.byteforce.seedj.core.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 写入专用派生字段：HMAC-SHA256(scope + 0x00 + 原始值)。
 *
 * <p>scope 形如 {@code seedj:<extension>:<object>:<field>:v1}；输出 64 位小写 hex。 轮换窗口内 {@link
 * #variants(String)} 覆盖全部接受密钥，用于防重/匹配。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DerivedField {

    private static final Pattern SCOPE =
            Pattern.compile("^seedj:[a-z0-9_.-]+:[a-z0-9_.-]+:[a-z0-9_.-]+:v[0-9]+$");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String scope;

    private final DerivedFieldKeyRing keys;

    /**
     * 构造派生字段。
     *
     * @param scope 域分离 scope
     * @param keys 密钥环
     */
    public DerivedField(String scope, DerivedFieldKeyRing keys) {
        if (scope == null || !SCOPE.matcher(scope).matches()) {
            throw new IllegalArgumentException("illegal derived-field scope: " + scope);
        }
        if (keys == null) {
            throw new IllegalArgumentException("keys are required");
        }
        this.scope = scope;
        this.keys = keys;
    }

    /**
     * 用主密钥派生。
     *
     * @param raw 原始值
     * @return 64 位小写 hex
     */
    public String derive(String raw) {
        return hmac(keys.primary(), raw);
    }

    /**
     * 用密钥环全部密钥派生（主密钥在前）。
     *
     * @param raw 原始值
     * @return 散列变体
     */
    public List<String> variants(String raw) {
        List<String> variants = new ArrayList<>(keys.all().size());
        for (String key : keys.all()) {
            variants.add(hmac(key, raw));
        }
        return List.copyOf(variants);
    }

    /**
     * 原始值是否匹配任一已接受散列。
     *
     * @param raw 原始值
     * @param expected 已存散列
     * @return 是否匹配
     */
    public boolean matches(String raw, String expected) {
        return expected != null && variants(raw).contains(expected);
    }

    private String hmac(String key, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("raw value must not be blank");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                out[i * 2] = HEX[value >>> 4];
                out[i * 2 + 1] = HEX[value & 0x0f];
            }
            return new String(out);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HmacSHA256 unavailable", error);
        }
    }
}
