package cn.byteforce.seedj.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 业务字段声明：类型 + 必填 + 写入专用 + 事件可见 + 可过滤。
 *
 * <p>{@code writeOnly} 与 {@code eventVisible} 互斥：写入专用的敏感字段不得进入事件白名单，
 * 避免脱敏与事件投影两套规则各说各话。</p>
 *
 * @param name         字段名（小写标识符）
 * @param type         字段类型
 * @param required     是否必填
 * @param writeOnly    是否写入专用（读投影与事件都不输出）
 * @param eventVisible 是否进入事件载荷
 * @param filterable   是否允许作为列表过滤/排序字段
 * @author ByteForce
 * @since 1.0.0
 */
public record FieldDefinition(String name, FieldType type, boolean required, boolean writeOnly,
                              boolean eventVisible, boolean filterable) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    public FieldDefinition {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal field name: " + name);
        }
        type = Objects.requireNonNull(type, "type");
        if (writeOnly && eventVisible) {
            throw new IllegalArgumentException(
                    "field " + name + " cannot be writeOnly and eventVisible");
        }
    }

    /**
     * 普通可读字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition of(String name, FieldType type) {
        return new FieldDefinition(name, type, false, false, true, false);
    }

    /**
     * 必填可读字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition required(String name, FieldType type) {
        return new FieldDefinition(name, type, true, false, true, false);
    }

    /**
     * 写入专用字段（不进入读投影与事件）。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition writeOnly(String name, FieldType type) {
        return new FieldDefinition(name, type, false, true, false, false);
    }

    /**
     * 可过滤字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition filterable(String name, FieldType type) {
        return new FieldDefinition(name, type, false, false, true, true);
    }
}
