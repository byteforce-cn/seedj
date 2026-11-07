package cn.byteforce.seedj.core.security;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 请求事实的声明类型：写入与读取都按它校验，类型不匹配显式失败。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ScopeKeyType {

    /** 单个字符串。 */
    STRING,

    /** 任意数值。 */
    NUMBER,

    /** 布尔值。 */
    BOOLEAN,

    /** 业务日期（{@link LocalDate}）。 */
    DATE,

    /** 字符串列表。 */
    LIST_STRING,

    /** UUID 列表。 */
    LIST_UUID;

    /**
     * 校验并规范化一个事实值。
     *
     * @param key key 名（用于错误信息）
     * @param value 原始值
     * @return 规范化后的值
     */
    public Object validate(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("scope fact " + key + " must not be null");
        }
        return switch (this) {
            case STRING -> require(key, value, String.class);
            case NUMBER -> require(key, value, Number.class);
            case BOOLEAN -> require(key, value, Boolean.class);
            case DATE -> require(key, value, LocalDate.class);
            case LIST_STRING -> list(key, value, String.class);
            case LIST_UUID -> list(key, value, UUID.class);
        };
    }

    private static Object require(String key, Object value, Class<?> type) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "scope fact "
                            + key
                            + " must be "
                            + type.getSimpleName()
                            + ", got: "
                            + value.getClass().getName());
        }
        return value;
    }

    private static List<?> list(String key, Object value, Class<?> elementType) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("scope fact " + key + " must be a list");
        }
        for (Object element : values) {
            if (!elementType.isInstance(element)) {
                throw new IllegalArgumentException(
                        "scope fact "
                                + key
                                + " must contain "
                                + elementType.getSimpleName()
                                + " values");
            }
        }
        return List.copyOf(values);
    }
}
