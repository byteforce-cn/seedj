package cn.byteforce.seedj.starter.eis.authz;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 主体属性集合（{@code attributes.api.yaml} 的 {@code AttributeSet}）：主岗单值 +
 * 多岗集合（ATR-003 形状），提供属性条件求值。
 *
 * <p>未建模的契约附加字段（organization_units / reporting_chain 等）经 {@link #getRaw()}
 * 透传，保证前向兼容。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzAttributeSet {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String employeeStatus;

    private final String employmentType;

    private final EisAuthzPositionRef primaryPosition;

    private final List<EisAuthzPositionRef> positions;

    private final JsonNode raw;

    /**
     * 构造属性集合。
     *
     * @param employeeStatus 雇佣状态（可空）
     * @param employmentType 雇佣类型（可空）
     * @param primaryPosition 主岗（可空）
     * @param positions       多岗集合（含主岗；可空）
     * @param raw             原始 JSON（可空）
     */
    public EisAuthzAttributeSet(String employeeStatus, String employmentType,
                                EisAuthzPositionRef primaryPosition, List<EisAuthzPositionRef> positions,
                                JsonNode raw) {
        this.employeeStatus = employeeStatus;
        this.employmentType = employmentType;
        this.primaryPosition = primaryPosition;
        List<EisAuthzPositionRef> parsed = positions == null ? List.of() : List.copyOf(positions);
        if (parsed.isEmpty() && primaryPosition != null) {
            // 契约要求 positions 含主岗；缺失时按含主岗容错
            parsed = List.of(primaryPosition);
        }
        this.positions = parsed;
        this.raw = raw;
    }

    /**
     * 空属性集合。
     *
     * @return 空集合
     */
    public static EisAuthzAttributeSet empty() {
        return new EisAuthzAttributeSet(null, null, null, List.of(), null);
    }

    /**
     * 从契约 JSON 解析（缺失/非对象 → 空集合）。
     *
     * @param node AttributeSet JSON
     * @return 属性集合
     */
    public static EisAuthzAttributeSet fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            return empty();
        }
        EisAuthzPositionRef primary = node.get("primary_position") == null || node.get("primary_position").isNull()
                ? null : EisAuthzPositionRef.fromJson(node.get("primary_position"));
        List<EisAuthzPositionRef> positions = new ArrayList<>();
        JsonNode positionsNode = node.get("positions");
        if (positionsNode != null && positionsNode.isArray()) {
            for (JsonNode item : positionsNode) {
                positions.add(EisAuthzPositionRef.fromJson(item));
            }
        }
        return new EisAuthzAttributeSet(text(node, "employee_status"), text(node, "employment_type"), primary,
                positions, node);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * 属性条件求值（ATR-003/007)：键显式区分主岗与任一岗位。
     *
     * @param condition 条件
     * @return 满足为 {@code true}
     */
    public boolean satisfies(EisAuthzAttributeCondition condition) {
        String key = condition.getKey();
        List<String> expected = condition.getExpectedValues();
        if ("employee_status".equals(key)) {
            return matches(employeeStatus, expected);
        }
        if ("employment_type".equals(key)) {
            return matches(employmentType, expected);
        }
        if (key.startsWith(EisAuthzAttributeCondition.PRIMARY_POSITION_PREFIX)) {
            String field = key.substring(EisAuthzAttributeCondition.PRIMARY_POSITION_PREFIX.length());
            return primaryPosition != null && matches(primaryPosition.fieldValue(field), expected);
        }
        if (key.startsWith(EisAuthzAttributeCondition.ANY_POSITION_PREFIX)) {
            String field = key.substring(EisAuthzAttributeCondition.ANY_POSITION_PREFIX.length());
            for (EisAuthzPositionRef position : positions) {
                if (matches(position.fieldValue(field), expected)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean matches(String actual, List<String> expected) {
        return actual != null && expected.contains(actual);
    }

    /**
     * 序列化为契约 {@code AttributeSet} 形状。
     *
     * <p>优先回放原始 JSON（{@link #getRaw()}，保证未建模字段如 {@code organization_units}
     * 在往返中不丢失）；未持有原始 JSON 时按已建模字段重建。</p>
     *
     * @return AttributeSet JSON
     */
    public ObjectNode toJson() {
        if (raw instanceof ObjectNode rawObject) {
            return rawObject.deepCopy();
        }
        ObjectNode node = MAPPER.createObjectNode();
        if (employeeStatus != null) {
            node.put("employee_status", employeeStatus);
        }
        if (employmentType != null) {
            node.put("employment_type", employmentType);
        }
        if (primaryPosition != null) {
            node.set("primary_position", primaryPosition.toJson());
        }
        ArrayNode positionNodes = node.putArray("positions");
        for (EisAuthzPositionRef position : positions) {
            positionNodes.add(position.toJson());
        }
        return node;
    }

    /**
     * 获取雇佣状态。
     *
     * @return 雇佣状态；可空
     */
    public String getEmployeeStatus() {
        return employeeStatus;
    }

    /**
     * 获取雇佣类型。
     *
     * @return 雇佣类型；可空
     */
    public String getEmploymentType() {
        return employmentType;
    }

    /**
     * 获取主岗。
     *
     * @return 主岗；可空
     */
    public EisAuthzPositionRef getPrimaryPosition() {
        return primaryPosition;
    }

    /**
     * 获取多岗集合（含主岗）。
     *
     * @return 岗位列表
     */
    public List<EisAuthzPositionRef> getPositions() {
        return positions;
    }

    /**
     * 获取原始 JSON（未建模字段透传）。
     *
     * @return 原始 JSON；可空
     */
    public JsonNode getRaw() {
        return raw;
    }
}
