package cn.byteforce.seedj.audit;

import tools.jackson.databind.JsonNode;

/**
 * 审计追加请求。
 *
 * <p>动作 = 命令类型；resource 由运行时按内核语义推导；payload 由调用方按契约
 * 构造（契约固定为 {@code {command_id, event_ids}}）。</p>
 *
 * @param <ID> 审计标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class AuditAppendRequest<ID> {

    private final String action;

    private final ID actor;

    private final String resourceType;

    private final ID resourceId;

    private final JsonNode payload;

    /**
     * 构造追加请求。
     *
     * @param action       动作（命令类型）
     * @param actor        操作人
     * @param resourceType 资源类型
     * @param resourceId   资源 ID（可空）
     * @param payload      载荷（可为空）
     */
    public AuditAppendRequest(String action, ID actor, String resourceType, ID resourceId,
                              JsonNode payload) {
        this.action = action;
        this.actor = actor;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.payload = payload;
    }

    /**
     * 获取动作。
     *
     * @return 动作
     */
    public String getAction() {
        return action;
    }

    /**
     * 获取操作人。
     *
     * @return 操作人 ID
     */
    public ID getActor() {
        return actor;
    }

    /**
     * 获取资源类型。
     *
     * @return 资源类型
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * 获取资源 ID。
     *
     * @return 资源 ID；可空
     */
    public ID getResourceId() {
        return resourceId;
    }

    /**
     * 获取载荷。
     *
     * @return 载荷；可空
     */
    public JsonNode getPayload() {
        return payload;
    }
}
