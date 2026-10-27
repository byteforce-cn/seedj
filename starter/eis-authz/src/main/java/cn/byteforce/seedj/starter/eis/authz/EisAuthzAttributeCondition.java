package cn.byteforce.seedj.starter.eis.authz;

import java.util.List;
import java.util.Objects;

/**
 * 属性条件声明（ABAC）：键显式编码语义域，避免 ATR-003 的「主岗 vs 任一岗位」静默择一。
 *
 * <p>支持的条件键：</p>
 * <ul>
 *   <li>{@code employee_status} / {@code employment_type}——雇佣状态/类型；</li>
 *   <li>{@code primary_position.<field>}——仅主岗（单值）；</li>
 *   <li>{@code positions[*].<field>}——任一岗位。</li>
 * </ul>
 *
 * <p>{@code <field>} ∈ position_id / code / title / grade / org_unit_id / org_unit_code；
 * 期望值为集合语义（单个即等值，多个即 in）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzAttributeCondition {

    /** 主岗条件键前缀。 */
    public static final String PRIMARY_POSITION_PREFIX = "primary_position.";

    /** 任一岗位条件键前缀。 */
    public static final String ANY_POSITION_PREFIX = "positions[*].";

    private static final String EMPLOYEE_STATUS = "employee_status";

    private static final String EMPLOYMENT_TYPE = "employment_type";

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final List<String> POSITION_FIELDS =
            List.of("position_id", "code", "title", "grade", "org_unit_id", "org_unit_code");

    private final String key;

    private final List<String> expectedValues;

    private EisAuthzAttributeCondition(String key, List<String> expectedValues) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("属性条件键不能为空");
        }
        if (expectedValues == null || expectedValues.isEmpty()) {
            throw new IllegalArgumentException("属性条件期望值不能为空: " + key);
        }
        for (String value : expectedValues) {
            if (value == null) {
                throw new IllegalArgumentException("属性条件期望值不能包含 null: " + key);
            }
        }
        this.key = key;
        this.expectedValues = List.copyOf(expectedValues);
    }

    /**
     * 雇佣状态条件（{@code employee_status}）。
     *
     * @param values 期望值（集合语义）
     * @return 条件
     */
    public static EisAuthzAttributeCondition employeeStatus(String... values) {
        return new EisAuthzAttributeCondition(EMPLOYEE_STATUS, List.of(values));
    }

    /**
     * 雇佣类型条件（{@code employment_type}）。
     *
     * @param values 期望值（集合语义）
     * @return 条件
     */
    public static EisAuthzAttributeCondition employmentType(String... values) {
        return new EisAuthzAttributeCondition(EMPLOYMENT_TYPE, List.of(values));
    }

    /**
     * 主岗条件（{@code primary_position.<field>}；单值主岗）。
     *
     * @param field  岗位字段
     * @param values 期望值（集合语义）
     * @return 条件
     */
    public static EisAuthzAttributeCondition primaryPosition(String field, String... values) {
        return new EisAuthzAttributeCondition(positionKey(PRIMARY_POSITION_PREFIX, field), List.of(values));
    }

    /**
     * 任一岗位条件（{@code positions[*].<field>}）。
     *
     * @param field  岗位字段
     * @param values 期望值（集合语义）
     * @return 条件
     */
    public static EisAuthzAttributeCondition anyPosition(String field, String... values) {
        return new EisAuthzAttributeCondition(positionKey(ANY_POSITION_PREFIX, field), List.of(values));
    }

    /**
     * 从 JSON 声明解析条件（控制器扩展字段 {@code require_attribute} 的元素）。
     *
     * <p>形状：{@code {"key":"primary_position.grade","values":["P5"]}}。</p>
     *
     * @param node 条件 JSON
     * @return 条件
     * @throws IllegalArgumentException 形状非法或键不受支持
     */
    public static EisAuthzAttributeCondition fromJson(tools.jackson.databind.JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("属性条件必须为 JSON 对象");
        }
        tools.jackson.databind.JsonNode keyNode = node.get("key");
        if (keyNode == null || !keyNode.isString() || keyNode.stringValue().isBlank()) {
            throw new IllegalArgumentException("属性条件缺少 key");
        }
        tools.jackson.databind.JsonNode valuesNode = node.get("values");
        if (valuesNode == null || !valuesNode.isArray() || valuesNode.isEmpty()) {
            throw new IllegalArgumentException("属性条件缺少 values");
        }
        java.util.List<String> values = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode item : valuesNode) {
            if (!item.isString()) {
                throw new IllegalArgumentException("属性条件 values 元素必须为字符串");
            }
            values.add(item.stringValue());
        }
        String key = keyNode.stringValue();
        requireSupportedKey(key);
        return new EisAuthzAttributeCondition(key, values);
    }

    private static String positionKey(String prefix, String field) {
        if (field == null || !POSITION_FIELDS.contains(field)) {
            throw new IllegalArgumentException("不支持的岗位字段: " + field + "（可选: " + POSITION_FIELDS + "）");
        }
        return prefix + field;
    }

    private static void requireSupportedKey(String key) {
        if (EMPLOYEE_STATUS.equals(key) || EMPLOYMENT_TYPE.equals(key)) {
            return;
        }
        if (key.startsWith(PRIMARY_POSITION_PREFIX)) {
            requirePositionField(key.substring(PRIMARY_POSITION_PREFIX.length()));
            return;
        }
        if (key.startsWith(ANY_POSITION_PREFIX)) {
            requirePositionField(key.substring(ANY_POSITION_PREFIX.length()));
            return;
        }
        throw new IllegalArgumentException("不支持的属性条件键: " + key);
    }

    private static void requirePositionField(String field) {
        if (!POSITION_FIELDS.contains(field)) {
            throw new IllegalArgumentException("不支持的岗位字段: " + field + "（可选: " + POSITION_FIELDS + "）");
        }
    }

    /**
     * 序列化为契约声明形状（{@code require_attribute} 的元素）。
     *
     * @return 条件 JSON（{@code {"key":...,"values":[...]}}）
     */
    public tools.jackson.databind.node.ObjectNode toJson() {
        tools.jackson.databind.node.ObjectNode node = MAPPER.createObjectNode();
        node.put("key", key);
        tools.jackson.databind.node.ArrayNode values = node.putArray("values");
        for (String value : expectedValues) {
            values.add(value);
        }
        return node;
    }

    /**
     * 获取规范化条件键。
     *
     * @return 条件键
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取期望值（集合语义）。
     *
     * @return 期望值列表
     */
    public List<String> getExpectedValues() {
        return expectedValues;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EisAuthzAttributeCondition that)) {
            return false;
        }
        return key.equals(that.key) && expectedValues.equals(that.expectedValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, expectedValues);
    }

    @Override
    public String toString() {
        return "EisAuthzAttributeCondition{" + key + " in " + expectedValues + "}";
    }
}
