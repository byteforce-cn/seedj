package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 业务对象 schema：字段的单一来源。
 *
 * <p>由它派生物理表、DDL、事件白名单、读投影脱敏与命令校验；业务代码不再手工维护
 * command record → fields helper → table → test fixture → DDL 之间的同一份字段列表。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ObjectSchema {

    /** 双时间轴元字段；领域字段不得与之同名。 */
    public static final Set<String> META_FIELDS = Set.of(
            "id", "version", "valid_from", "valid_to", "recorded_at", "superseded_at",
            "created_by", "created_at", "updated_by", "updated_at");

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final String objectType;

    private final String tableName;

    private final List<FieldDefinition> fields;

    private final Map<String, FieldDefinition> byName;

    private ObjectSchema(String objectType, String tableName, List<FieldDefinition> fields) {
        this.objectType = identifier(objectType, "objectType");
        this.tableName = identifier(tableName, "tableName");
        Objects.requireNonNull(fields, "fields");
        Map<String, FieldDefinition> index = new LinkedHashMap<>();
        List<FieldDefinition> copy = new ArrayList<>(fields.size());
        for (FieldDefinition field : fields) {
            Objects.requireNonNull(field, "field");
            if (META_FIELDS.contains(field.name())) {
                throw new IllegalArgumentException(
                        "field clashes with temporal meta field: " + field.name());
            }
            FieldDefinition previous = index.putIfAbsent(field.name(), field);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate field: " + field.name());
            }
            copy.add(field);
        }
        this.fields = List.copyOf(copy);
        this.byName = Collections.unmodifiableMap(index);
    }

    /**
     * 声明式构造器。
     *
     * @param objectType 对象类型
     * @param tableName  物理表名
     * @return 构造器
     */
    public static Builder builder(String objectType, String tableName) {
        return new Builder(objectType, tableName);
    }

    public String objectType() {
        return objectType;
    }

    public String tableName() {
        return tableName;
    }

    public List<FieldDefinition> fields() {
        return fields;
    }

    /**
     * 全部字段名。
     *
     * @return 不可变字段名集合（保持声明顺序）
     */
    public Set<String> fieldNames() {
        return byName.keySet();
    }

    /**
     * 按名取字段。
     *
     * @param name 字段名
     * @return 字段；未声明为空
     */
    public Optional<FieldDefinition> field(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    /**
     * 按名取字段，未声明即失败。
     *
     * @param name 字段名
     * @return 字段
     */
    public FieldDefinition requireField(String name) {
        return field(name).orElseThrow(
                () -> new IllegalArgumentException("unknown field: " + name));
    }

    /**
     * 写入专用字段（读投影剔除）。
     *
     * @return 不可变集合
     */
    public Set<String> writeOnlyFields() {
        Set<String> result = new LinkedHashSet<>();
        for (FieldDefinition field : fields) {
            if (field.writeOnly()) {
                result.add(field.name());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 进入事件载荷的字段（顺序与声明一致）。
     *
     * @return 字段名列表
     */
    public List<String> eventFields() {
        return fields.stream().filter(FieldDefinition::eventVisible).map(FieldDefinition::name)
                .toList();
    }

    /**
     * 必填字段。
     *
     * @return 字段名列表
     */
    public List<String> requiredFields() {
        return fields.stream().filter(FieldDefinition::required).map(FieldDefinition::name)
                .toList();
    }

    /**
     * 可过滤/排序字段。
     *
     * @return 不可变集合
     */
    public Set<String> filterableFields() {
        Set<String> result = new LinkedHashSet<>();
        for (FieldDefinition field : fields) {
            if (field.filterable()) {
                result.add(field.name());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 可排序字段。
     *
     * @return 不可变集合
     */
    public Set<String> sortableFields() {
        Set<String> result = new LinkedHashSet<>();
        for (FieldDefinition field : fields) {
            if (field.sortable()) {
                result.add(field.name());
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 按 schema 白名单收敛事件载荷。
     *
     * <p>只保留 {@link FieldDefinition#eventVisible()} 为真的字段；writeOnly 与敏感字段
     * 即使被 handler 放进 payload 也不会进入 outbox。schema 外的键视为 handler 错误，
     * 失败关闭而不是静默丢弃，避免拼写错误导致“事件少了一个字段”。</p>
     *
     * @param payload handler 产出的事件载荷
     * @return 只含可见字段的事件载荷（保持声明顺序）
     * @throws IllegalStateException payload 含 schema 外字段
     */
    public Map<String, Object> eventPayload(Map<String, ?> payload) {
        Map<String, ?> input = payload == null ? Map.of() : payload;
        Set<String> unknown = new TreeSet<>(input.keySet());
        unknown.removeAll(byName.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "unknown event payload fields for " + objectType + ": " + unknown);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            if (field.eventVisible() && input.containsKey(field.name())) {
                event.put(field.name(), input.get(field.name()));
            }
        }
        return event;
    }

    /**
     * 校验并类型化一份业务载荷。
     *
     * @param payload 原始载荷（HTTP Map）
     * @return 类型化载荷（保持声明顺序中出现的字段）
     */
    public Map<String, Object> validate(Map<String, Object> payload) {
        Map<String, Object> input = payload == null ? Map.of() : payload;
        Set<String> unknown = new TreeSet<>(input.keySet());
        unknown.removeAll(byName.keySet());
        if (!unknown.isEmpty()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown fields for " + objectType + ": " + unknown);
        }
        List<String> missing = new ArrayList<>();
        for (FieldDefinition field : fields) {
            if (field.required() && input.get(field.name()) == null) {
                missing.add(field.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "missing required fields for " + objectType + ": " + missing);
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            FieldDefinition field = byName.get(entry.getKey());
            try {
                typed.put(entry.getKey(), field.type().coerce(entry.getValue()));
            } catch (ContractException error) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "field " + entry.getKey() + ": " + error.reason());
            }
        }
        return typed;
    }

    private static String identifier(String value, String role) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("illegal " + role + ": " + value);
        }
        return value;
    }

    /** schema 构造器。 */
    public static final class Builder {

        private final String objectType;

        private final String tableName;

        private final List<FieldDefinition> fields = new ArrayList<>();

        private Builder(String objectType, String tableName) {
            this.objectType = objectType;
            this.tableName = tableName;
        }

        public Builder field(FieldDefinition field) {
            fields.add(field);
            return this;
        }

        public Builder field(String name, FieldType type) {
            return field(FieldDefinition.of(name, type));
        }

        public Builder required(String name, FieldType type) {
            return field(FieldDefinition.required(name, type));
        }

        public Builder writeOnly(String name, FieldType type) {
            return field(FieldDefinition.writeOnly(name, type));
        }

        public Builder filterable(String name, FieldType type) {
            return field(FieldDefinition.filterable(name, type));
        }

        /**
         * 构造 schema。
         *
         * @return 不可变 schema
         */
        public ObjectSchema build() {
            return new ObjectSchema(objectType, tableName, fields);
        }
    }
}
