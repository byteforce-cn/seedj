package cn.byteforce.seedj.core.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 派生字段密钥环：逗号分隔、首为主；旧密钥保留到无在库依赖。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DerivedFieldKeyRing {

    /** 生产注入键名。 */
    public static final String ENVIRONMENT_KEY = "SEEDJ_FIELD_HASH_KEYS";

    private final List<String> keys;

    /**
     * 构造密钥环。
     *
     * @param keys 至少一个非空密钥，首为主
     */
    public DerivedFieldKeyRing(String... keys) {
        if (keys == null || keys.length == 0) {
            throw new IllegalArgumentException("at least one derived-field key is required");
        }
        List<String> copy = new ArrayList<>(keys.length);
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("derived-field key must not be blank");
            }
            copy.add(key);
        }
        this.keys = List.copyOf(copy);
    }

    /**
     * 从环境变量读取；未注入时启动失败（生产不得回退到测试密钥）。
     *
     * @return 密钥环
     * @throws IllegalStateException 环境变量缺失或为空
     */
    public static DerivedFieldKeyRing fromEnvironment() {
        String raw = System.getenv(ENVIRONMENT_KEY);
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("seedj: " + ENVIRONMENT_KEY
                    + " is required for derived fields; refusing to fall back to a test key");
        }
        return new DerivedFieldKeyRing(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new));
    }

    /**
     * 显式测试密钥环。
     *
     * @param keys 测试密钥
     * @return 密钥环
     */
    public static DerivedFieldKeyRing forTests(String... keys) {
        return new DerivedFieldKeyRing(keys);
    }

    /**
     * 主密钥。
     *
     * @return 主密钥
     */
    public String primary() {
        return keys.get(0);
    }

    /**
     * 全部接受密钥（主密钥在前）。
     *
     * @return 不可变列表
     */
    public List<String> all() {
        return keys;
    }
}
