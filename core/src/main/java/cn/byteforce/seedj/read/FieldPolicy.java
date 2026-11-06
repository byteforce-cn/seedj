package cn.byteforce.seedj.read;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Privacy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 字段到访问结果的不可变映射。
 *
 * @param fields 字段 → 访问结果
 * @author ByteForce
 * @since 1.0.0
 */
public record FieldPolicy(Map<String, FieldAccess> fields) {

    public FieldPolicy {
        Objects.requireNonNull(fields, "fields");
        Map<String, FieldAccess> copy = new LinkedHashMap<>();
        fields.forEach((field, access) -> copy.put(field, Objects.requireNonNull(access, "access")));
        fields = Collections.unmodifiableMap(copy);
    }

    /**
     * 按 schema 生成默认策略：{@code PUBLIC/INTERNAL → FULL}，
     * {@code SENSITIVE/RESTRICTED → HIDDEN}，{@code writeOnly → HIDDEN}。
     *
     * @param schema 对象 schema
     * @return 默认策略
     */
    public static FieldPolicy defaults(ObjectSchema schema) {
        Objects.requireNonNull(schema, "schema");
        Map<String, FieldAccess> fields = new LinkedHashMap<>();
        for (FieldDefinition field : schema.fields()) {
            FieldAccess.Action action;
            if (field.writeOnly()) {
                action = FieldAccess.Action.HIDDEN;
            } else if (field.privacy().ordinal() >= Privacy.SENSITIVE.ordinal()) {
                action = FieldAccess.Action.HIDDEN;
            } else {
                action = FieldAccess.Action.FULL;
            }
            fields.put(field.name(), new FieldAccess(action, null));
        }
        return new FieldPolicy(fields);
    }

    /**
     * 与另一份策略合并，取最窄动作；同动作保留先出现的策略 id。
     *
     * @param other 另一份策略
     * @return 合并后的策略
     */
    public FieldPolicy narrow(FieldPolicy other) {
        Objects.requireNonNull(other, "other");
        Map<String, FieldAccess> merged = new LinkedHashMap<>(fields);
        other.fields.forEach((field, access) -> merged.merge(field, access,
                (left, right) -> narrower(left, right)));
        return new FieldPolicy(merged);
    }

    private static FieldAccess narrower(FieldAccess left, FieldAccess right) {
        return right.action().ordinal() > left.action().ordinal() ? right : left;
    }
}
