package cn.byteforce.seedj.codegen.processor;

import cn.byteforce.seedj.codegen.MaskerName;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.Privacy;
import java.util.Objects;

/**
 * 由 record 组件推导出的字段模型（纯数据，便于单测渲染结果）。
 *
 * @param name 字段名
 * @param type 字段类型
 * @param javaType 原 Java 类型（生成读视图用）
 * @param required 是否必填
 * @param writeOnly 是否写入专用
 * @param filterable 是否可过滤
 * @param sortable 是否可排序
 * @param indexed 是否需要当前状态索引
 * @param privacy 隐私层级
 * @param masker 默认打码规则
 * @param constraints 值域约束
 * @author ByteForce
 * @since 1.0.0
 */
record FieldModel(
        String name,
        FieldType type,
        String javaType,
        boolean required,
        boolean writeOnly,
        boolean filterable,
        boolean sortable,
        boolean indexed,
        Privacy privacy,
        MaskerName masker,
        FieldDefinition.Constraints constraints) {

    FieldModel {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(privacy, "privacy");
        Objects.requireNonNull(masker, "masker");
        constraints = constraints == null ? FieldDefinition.Constraints.NONE : constraints;
    }

    /**
     * 兼容既有模型：没有值域约束。
     *
     * @param name 字段名
     * @param type 字段类型
     * @param javaType 原 Java 类型
     * @param required 是否必填
     * @param writeOnly 是否写入专用
     * @param filterable 是否可过滤
     * @param sortable 是否可排序
     * @param indexed 是否需要当前状态索引
     * @param privacy 隐私层级
     * @param masker 默认打码规则
     */
    FieldModel(
            String name,
            FieldType type,
            String javaType,
            boolean required,
            boolean writeOnly,
            boolean filterable,
            boolean sortable,
            boolean indexed,
            Privacy privacy,
            MaskerName masker) {
        this(
                name,
                type,
                javaType,
                required,
                writeOnly,
                filterable,
                sortable,
                indexed,
                privacy,
                masker,
                FieldDefinition.Constraints.NONE);
    }

    /**
     * 是否进入事件载荷：写入专用与敏感/受限字段都不进入。
     *
     * @return 进入事件为 {@code true}
     */
    boolean eventVisible() {
        return !writeOnly && privacy.ordinal() < Privacy.SENSITIVE.ordinal();
    }
}
