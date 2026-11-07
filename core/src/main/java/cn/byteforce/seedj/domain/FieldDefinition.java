package cn.byteforce.seedj.domain;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务字段声明：类型、读写约束、过滤/排序/索引能力与隐私层级。
 *
 * <p>{@code writeOnly} 与 {@code eventVisible} 互斥：写入专用的敏感字段不得进入事件白名单。 {@code SENSITIVE}/{@code
 * RESTRICTED} 字段也不得进入事件白名单：事件流面向订阅方， 敏感字段默认不得通过 outbox 泄漏。
 *
 * <p>{@code writeOnly} 与 {@code privacy} 正交：写入专用是“永远不出写路径”， 优先于任何字段策略。
 *
 * @param name 字段名（小写标识符）
 * @param type 字段类型
 * @param required 是否必填
 * @param writeOnly 是否写入专用（读投影与事件都不输出）
 * @param eventVisible 是否进入事件载荷
 * @param filterable 是否允许作为列表过滤字段
 * @param sortable 是否允许作为排序字段
 * @param indexed 是否生成当前状态部分索引
 * @param privacy 隐私层级；{@code null} 归一为 {@link Privacy#PUBLIC}
 * @param masker 打码器；{@code null} 表示无默认打码规则
 * @param constraints 值域约束（enum/pattern/length/precision/unique）
 * @author ByteForce
 * @since 1.0.0
 */
public record FieldDefinition(
        String name,
        FieldType type,
        boolean required,
        boolean writeOnly,
        boolean eventVisible,
        boolean filterable,
        boolean sortable,
        boolean indexed,
        Privacy privacy,
        Masker masker,
        Constraints constraints) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    public FieldDefinition {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("illegal field name: " + name);
        }
        type = Objects.requireNonNull(type, "type");
        privacy = privacy == null ? Privacy.PUBLIC : privacy;
        constraints = constraints == null ? Constraints.NONE : constraints;
        if (writeOnly && eventVisible) {
            throw new IllegalArgumentException(
                    "field " + name + " cannot be writeOnly and eventVisible");
        }
        if (privacy.ordinal() >= Privacy.SENSITIVE.ordinal() && eventVisible) {
            throw new IllegalArgumentException(
                    "field " + name + " is " + privacy + " and cannot be eventVisible");
        }
        if (!constraints.enumValues().isEmpty() && type != FieldType.TEXT) {
            throw new IllegalArgumentException(
                    "field " + name + " declares enum values but is not TEXT");
        }
        if (constraints.pattern() != null && type != FieldType.TEXT) {
            throw new IllegalArgumentException(
                    "field " + name + " declares a pattern but is not TEXT");
        }
        if ((constraints.minLength() != null || constraints.maxLength() != null)
                && type != FieldType.TEXT) {
            throw new IllegalArgumentException(
                    "field " + name + " declares length constraints but is not TEXT");
        }
        if ((constraints.precision() != null || constraints.scale() != null)
                && type != FieldType.DECIMAL) {
            throw new IllegalArgumentException(
                    "field " + name + " declares precision/scale but is not DECIMAL");
        }
    }

    /**
     * 兼容旧装配：没有值域约束。
     *
     * @param name 字段名
     * @param type 字段类型
     * @param required 是否必填
     * @param writeOnly 是否写入专用
     * @param eventVisible 是否进入事件载荷
     * @param filterable 是否可过滤
     * @param sortable 是否可排序
     * @param indexed 是否生成当前状态索引
     * @param privacy 隐私层级
     * @param masker 打码器
     */
    public FieldDefinition(
            String name,
            FieldType type,
            boolean required,
            boolean writeOnly,
            boolean eventVisible,
            boolean filterable,
            boolean sortable,
            boolean indexed,
            Privacy privacy,
            Masker masker) {
        this(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                indexed,
                privacy,
                masker,
                Constraints.NONE);
    }

    /**
     * 普通可读字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition of(String name, FieldType type) {
        return new FieldDefinition(
                name,
                type,
                false,
                false,
                true,
                false,
                false,
                false,
                Privacy.PUBLIC,
                null,
                Constraints.NONE);
    }

    /**
     * 必填可读字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition required(String name, FieldType type) {
        return new FieldDefinition(
                name,
                type,
                true,
                false,
                true,
                false,
                false,
                false,
                Privacy.PUBLIC,
                null,
                Constraints.NONE);
    }

    /**
     * 写入专用字段（不进入读投影与事件）。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition writeOnly(String name, FieldType type) {
        return new FieldDefinition(
                name,
                type,
                false,
                true,
                false,
                false,
                false,
                false,
                Privacy.PUBLIC,
                null,
                Constraints.NONE);
    }

    /**
     * 可过滤字段。
     *
     * @param name 字段名
     * @param type 类型
     * @return 声明
     */
    public static FieldDefinition filterable(String name, FieldType type) {
        return new FieldDefinition(
                name,
                type,
                false,
                false,
                true,
                true,
                false,
                false,
                Privacy.PUBLIC,
                null,
                Constraints.NONE);
    }

    /**
     * 标记必填。
     *
     * @return 新声明
     */
    public FieldDefinition withRequired() {
        return new FieldDefinition(
                name,
                type,
                true,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                indexed,
                privacy,
                masker,
                constraints);
    }

    /**
     * 标记可过滤。
     *
     * @return 新声明
     */
    public FieldDefinition withFilterable() {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                true,
                sortable,
                indexed,
                privacy,
                masker,
                constraints);
    }

    /**
     * 标记可排序。
     *
     * @return 新声明
     */
    public FieldDefinition withSortable() {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                true,
                indexed,
                privacy,
                masker,
                constraints);
    }

    /**
     * 标记需要当前状态索引。
     *
     * @return 新声明
     */
    public FieldDefinition withIndexed() {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                true,
                privacy,
                masker,
                constraints);
    }

    /**
     * 设置隐私层级。
     *
     * @param value 隐私层级
     * @return 新声明
     */
    public FieldDefinition withPrivacy(Privacy value) {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                indexed,
                value,
                masker,
                constraints);
    }

    /**
     * 设置打码器。
     *
     * @param value 打码器
     * @return 新声明
     */
    public FieldDefinition withMasker(Masker value) {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                indexed,
                privacy,
                value,
                constraints);
    }

    /**
     * 设置值域约束。
     *
     * @param value 约束
     * @return 新声明
     */
    public FieldDefinition withConstraints(Constraints value) {
        return new FieldDefinition(
                name,
                type,
                required,
                writeOnly,
                eventVisible,
                filterable,
                sortable,
                indexed,
                privacy,
                masker,
                value);
    }

    /**
     * 字段值域约束：由规范 Schema 的 enum/pattern/length/precision/unique 映射而来。
     *
     * <p>{@code unique} 是业务键唯一索引的声明（当前状态部分唯一索引），不是单行校验； 其余约束在 {@link ObjectSchema#validate} 中逐值校验。
     *
     * @param enumValues 允许的取值；空集表示不限制
     * @param pattern JSON Schema 与 POSIX 兼容正则；{@code null} 表示不限制
     * @param minLength 最小字符数；{@code null} 表示不限制
     * @param maxLength 最大字符数；{@code null} 表示不限制
     * @param unique 是否要求当前状态唯一（业务键）
     * @param precision 十进制总位数；{@code null} 表示不限制
     * @param scale 小数位数；{@code null} 表示不限制
     * @author ByteForce
     * @since 1.0.0
     */
    public record Constraints(
            Set<String> enumValues,
            String pattern,
            Integer minLength,
            Integer maxLength,
            boolean unique,
            Integer precision,
            Integer scale) {

        /** 无约束。 */
        public static final Constraints NONE =
                new Constraints(Set.of(), null, null, null, false, null, null);

        public Constraints {
            enumValues = enumValues == null ? Set.of() : Set.copyOf(enumValues);
            pattern = pattern == null || pattern.isBlank() ? null : pattern;
            if (pattern != null) {
                Pattern.compile(pattern);
            }
            if (minLength != null && minLength < 0) {
                throw new IllegalArgumentException("minLength must be >= 0");
            }
            if (maxLength != null && maxLength < 0) {
                throw new IllegalArgumentException("maxLength must be >= 0");
            }
            if (minLength != null && maxLength != null && minLength > maxLength) {
                throw new IllegalArgumentException("minLength must be <= maxLength");
            }
            if (precision != null && precision < 1) {
                throw new IllegalArgumentException("precision must be >= 1");
            }
            if (scale != null && scale < 0) {
                throw new IllegalArgumentException("scale must be >= 0");
            }
            if (precision != null && scale != null && scale > precision) {
                throw new IllegalArgumentException("scale must be <= precision");
            }
        }
    }
}
