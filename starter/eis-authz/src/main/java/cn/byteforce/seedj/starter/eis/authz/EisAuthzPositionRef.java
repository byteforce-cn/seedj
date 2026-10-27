package cn.byteforce.seedj.starter.eis.authz;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 岗位引用（{@code attributes.api.yaml} 的 {@code PositionRef}；快照内嵌，避免消费方回查）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisAuthzPositionRef {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String positionId;

    private final String code;

    private final String title;

    private final String grade;

    private final String orgUnitId;

    private final String orgUnitCode;

    private final boolean primary;

    /**
     * 构造岗位引用。
     *
     * @param positionId  岗位 ID
     * @param code        岗位编码
     * @param title       岗位名称
     * @param grade       职级（可空）
     * @param orgUnitId   组织单元 ID
     * @param orgUnitCode 组织单元编码
     * @param primary     是否主岗
     */
    public EisAuthzPositionRef(String positionId, String code, String title, String grade, String orgUnitId,
                               String orgUnitCode, boolean primary) {
        this.positionId = positionId;
        this.code = code;
        this.title = title;
        this.grade = grade;
        this.orgUnitId = orgUnitId;
        this.orgUnitCode = orgUnitCode;
        this.primary = primary;
    }

    /**
     * 从契约 JSON 解析。
     *
     * @param node PositionRef JSON
     * @return 岗位引用
     */
    public static EisAuthzPositionRef fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("PositionRef 必须为 JSON 对象");
        }
        return new EisAuthzPositionRef(text(node, "position_id"), text(node, "code"), text(node, "title"),
                text(node, "grade"), text(node, "org_unit_id"), text(node, "org_unit_code"),
                node.get("is_primary") != null && node.get("is_primary").booleanValue());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * 序列化为契约 {@code PositionRef} 形状。
     *
     * @return PositionRef JSON
     */
    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("position_id", positionId);
        node.put("code", code);
        node.put("title", title);
        node.put("grade", grade);
        node.put("org_unit_id", orgUnitId);
        node.put("org_unit_code", orgUnitCode);
        node.put("is_primary", primary);
        return node;
    }

    /**
     * 按条件字段取值。
     *
     * @param field 字段名（position_id / code / title / grade / org_unit_id / org_unit_code）
     * @return 字段值；未知字段或缺失为 {@code null}
     */
    String fieldValue(String field) {
        return switch (field) {
            case "position_id" -> positionId;
            case "code" -> code;
            case "title" -> title;
            case "grade" -> grade;
            case "org_unit_id" -> orgUnitId;
            case "org_unit_code" -> orgUnitCode;
            default -> null;
        };
    }

    /**
     * 获取岗位 ID。
     *
     * @return 岗位 ID
     */
    public String getPositionId() {
        return positionId;
    }

    /**
     * 获取岗位编码。
     *
     * @return 岗位编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取岗位名称。
     *
     * @return 岗位名称
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取职级。
     *
     * @return 职级；可空
     */
    public String getGrade() {
        return grade;
    }

    /**
     * 获取组织单元 ID。
     *
     * @return 组织单元 ID
     */
    public String getOrgUnitId() {
        return orgUnitId;
    }

    /**
     * 获取组织单元编码。
     *
     * @return 组织单元编码
     */
    public String getOrgUnitCode() {
        return orgUnitCode;
    }

    /**
     * 是否主岗。
     *
     * @return 主岗为 {@code true}
     */
    public boolean isPrimary() {
        return primary;
    }
}
