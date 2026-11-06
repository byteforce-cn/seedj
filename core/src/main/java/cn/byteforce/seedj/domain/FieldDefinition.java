package cn.byteforce.seedj.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 业务字段声明：类型、读写约束、过滤/排序/索引能力与隐私层级。
 *
 * <p>{@code writeOnly} 与 {@code eventVisible} 互斥：写入专用的敏感字段不得进入事件白名单。
 * {@code SENSITIVE}/{@code RESTRICTED} 字段也不得进入事件白名单：事件流面向订阅方，
 * 敏感字段默认不得通过 outbox 泄漏。</p>
 *
 * <p>{@code writeOnly} 与 {@code privacy} 正交：写入专用是“永远不出写路径”，
 * 优先于任何字段策略。</p>
 *
 * @param name         字段名（小写标识符）
 * @param type         字段类型
 * @param required     是否必填
 * @param writeOnly    是否写入专用（读投影与事件都不输出）
 * @param eventVisible 是否进入事件载荷
 * @param filterable   是否允许作为列表过滤字段
 * @param sortable     是否允许作为排序字段
 * @param indexed      是否生成当前状态部分索引
 * @param privacy      隐私层级；{@code null} 归一为 {@link Privacy#PUBLIC}
 * @param masker       打码器；{@code null} 表示无默认打码规则
 * @author ByteForce
 * @since 1.0.0
 */
public record FieldDefinition(String name, FieldType type, boolean required, boolean writeOnly,
                              boolean eventVisible, boolean filterable, boolean sortable,
                              boolean indexed, Privacy privacy, Masker masker) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    public FieldDefinition {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal field name: " + name);
        }
        type = Objects.requireNonNull(type, "type");
        privacy = privacy == null ? Privacy.PUBLIC : privacy;
        if (writeOnly && eventVisible) {
            throw new IllegalArgumentException(
                    "field " + name + " cannot be writeOnly and eventVisible");
        }
        if (privacy.ordinal() >= Privacy.SENSITIVE.ordinal() && eventVisible) {
            throw new IllegalArgumentException(
                    "field " + name + " is " + privacy + " and cannot be eventVisible");
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
        return new FieldDefinition(name, type, false, false, true, false, false, false,
                Privacy.PUBLIC, null);
    }

    /**
     * 必填可读字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition required(String name, FieldType type) {
        return new FieldDefinition(name, type, true, false, true, false, false, false,
                Privacy.PUBLIC, null);
    }

    /**
     * 写入专用字段（不进入读投影与事件）。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition writeOnly(String name, FieldType type) {
        return new FieldDefinition(name, type, false, true, false, false, false, false,
                Privacy.PUBLIC, null);
    }

    /**
     * 可过滤字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition filterable(String name, FieldType type) {
        return new FieldDefinition(name, type, false, false, true, true, false, false,
                Privacy.PUBLIC, null);
    }

    /**
     * 标记必填。
     *
     * @return 新声明
     */
    public FieldDefinition withRequired() {
        return new FieldDefinition(name, type, true, writeOnly, eventVisible, filterable,
                sortable, indexed, privacy, masker);
    }

    /**
     * 标记可过滤。
     *
     * @return 新声明
     */
    public FieldDefinition withFilterable() {
        return new FieldDefinition(name, type, required, writeOnly, eventVisible, true,
                sortable, indexed, privacy, masker);
    }

    /**
     * 标记可排序。
     *
     * @return 新声明
     */
    public FieldDefinition withSortable() {
        return new FieldDefinition(name, type, required, writeOnly, eventVisible, filterable,
                true, indexed, privacy, masker);
    }

    /**
     * 标记需要当前状态索引。
     *
     * @return 新声明
     */
    public FieldDefinition withIndexed() {
        return new FieldDefinition(name, type, required, writeOnly, eventVisible, filterable,
                sortable, true, privacy, masker);
    }

    /**
     * 设置隐私层级。
     *
     * @param value 隐私层级
     * @return 新声明
     */
    public FieldDefinition withPrivacy(Privacy value) {
        return new FieldDefinition(name, type, required, writeOnly, eventVisible, filterable,
                sortable, indexed, value, masker);
    }

    /**
     * 设置打码器。
     *
     * @param value 打码器
     * @return 新声明
     */
    public FieldDefinition withMasker(Masker value) {
        return new FieldDefinition(name, type, required, writeOnly, eventVisible, filterable,
                sortable, indexed, privacy, value);
    }
}
