package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 审计追加 SPI：命令受理成功（accepted）时在同一事务内写入审计链。
 *
 * <p>语义权威为 {@code 05-audit-contract/hash-chain.spec.md}：链式哈希、序列连续、
 * 系统时间轴 append-only。运行时保证：仅 accepted 命令调用（核心 04 语义）；
 * 在命令事务内调用（审计与命令/事件同生共死）；锚行串行分配序列。</p>
 *
 * <p>实现由 {@code starter-eis-audit} 提供（{@link EisAuditAppender} 为必配 Bean，
 * 缺失时运行时不创建——审计是契约强制项，不是可选装饰）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisAuditAppender {

    /**
     * 追加一条审计记录（当前事务内）。
     *
     * @param request 追加请求
     */
    void append(EisAuditAppendRequest request);

    /**
     * 审计追加请求：动作 = 命令类型；resource 由运行时按内核语义推导
     * （首个事件的主体域），payload 固定为 {@code {command_id, event_ids}}。
     */
    final class EisAuditAppendRequest {

        private final String action;

        private final UUID actor;

        private final String resourceType;

        private final UUID resourceId;

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
        public EisAuditAppendRequest(String action, UUID actor, String resourceType, UUID resourceId,
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
         * @return 操作人 UUID
         */
        public UUID getActor() {
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
        public UUID getResourceId() {
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
}
