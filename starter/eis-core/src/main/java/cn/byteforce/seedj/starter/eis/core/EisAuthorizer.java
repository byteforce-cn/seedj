package cn.byteforce.seedj.starter.eis.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * 授权钩子 SPI：命令受理前的权限判定（权限串 = 命令类型）。
 *
 * <p>IMS 由 {@code starter-eis-authz} 提供权威实现（本地 {@code /check}）；
 * 拒绝时运行时以 {@code EIS-403-001} + reason（+reason_code）落库并响应。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EisAuthorizer {

    /**
     * 校验操作人是否可执行该命令。
     *
     * @param request 判定请求
     * @return 判定结果
     */
    EisAuthorizationDecision check(EisAuthorizationRequest request);

    /**
     * 判定请求（主体 × 命令类型 × 时点 × 作用对象 × 载荷）。
     */
    final class EisAuthorizationRequest {

        private final UUID actor;

        private final String commandType;

        private final Instant at;

        private final List<UUID> targets;

        private final JsonNode payload;

        /**
         * 构造判定请求（无作用对象与载荷；兼容旧版语义）。
         *
         * @param actor       操作人
         * @param commandType 命令类型（即所需权限串）
         * @param at          判定时点
         */
        public EisAuthorizationRequest(UUID actor, String commandType, Instant at) {
            this(actor, commandType, at, List.of(), null);
        }

        /**
         * 构造判定请求（携带本次命令的作用对象与原始载荷）。
         *
         * @param actor       操作人
         * @param commandType 命令类型（即所需权限串）
         * @param at          判定时点
         * @param targets     作用对象 ID 列表（可空 = 空列表；不携带对象类型，由决策器按
         *                    命令类型 / 载荷自行判定）
         * @param payload     命令原始载荷（可空；不受处理器脱敏视图影响，供决策器做目标绑定）
         */
        public EisAuthorizationRequest(UUID actor, String commandType, Instant at, List<UUID> targets,
                                       JsonNode payload) {
            this.actor = actor;
            this.commandType = commandType;
            this.at = at;
            this.targets = targets == null ? List.of() : List.copyOf(targets);
            this.payload = payload;
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
         * 获取命令类型。
         *
         * @return 命令类型
         */
        public String getCommandType() {
            return commandType;
        }

        /**
         * 获取判定时点。
         *
         * @return 时点
         */
        public Instant getAt() {
            return at;
        }

        /**
         * 获取作用对象 ID 列表。
         *
         * @return 对象 ID 列表（不可变；无作用对象时为空列表）
         */
        public List<UUID> getTargets() {
            return targets;
        }

        /**
         * 获取命令原始载荷。
         *
         * @return 载荷 JSON；旧版三参构造的请求为 {@code null}
         */
        public JsonNode getPayload() {
            return payload;
        }
    }

    /**
     * 判定结果（allowed + reason + 机器可读原因码）。
     */
    final class EisAuthorizationDecision {

        private final boolean allowed;

        private final String reason;

        private final String reasonCode;

        private EisAuthorizationDecision(boolean allowed, String reason, String reasonCode) {
            this.allowed = allowed;
            this.reason = reason;
            this.reasonCode = reasonCode;
        }

        /**
         * 放行判定。
         *
         * @return 判定结果
         */
        public static EisAuthorizationDecision allowed() {
            return new EisAuthorizationDecision(true, null, null);
        }

        /**
         * 拒绝判定。
         *
         * @param reason     人类可读理由
         * @param reasonCode 机器可读原因码（可空）
         * @return 判定结果
         */
        public static EisAuthorizationDecision denied(String reason, String reasonCode) {
            return new EisAuthorizationDecision(false, reason, reasonCode);
        }

        /**
         * 是否放行。
         *
         * @return 放行为 {@code true}
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 获取理由。
         *
         * @return 理由；放行时为 {@code null}
         */
        public String getReason() {
            return reason;
        }

        /**
         * 获取原因码。
         *
         * @return 原因码；无则为 {@code null}
         */
        public String getReasonCode() {
            return reasonCode;
        }
    }
}
