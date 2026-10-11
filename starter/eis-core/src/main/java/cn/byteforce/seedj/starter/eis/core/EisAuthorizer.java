package cn.byteforce.seedj.starter.eis.core;

import java.time.Instant;
import java.util.UUID;

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
     * 判定请求（主体 × 命令类型 × 时点）。
     */
    final class EisAuthorizationRequest {

        private final UUID actor;

        private final String commandType;

        private final Instant at;

        /**
         * 构造判定请求。
         *
         * @param actor       操作人
         * @param commandType 命令类型（即所需权限串）
         * @param at          判定时点
         */
        public EisAuthorizationRequest(UUID actor, String commandType, Instant at) {
            this.actor = actor;
            this.commandType = commandType;
            this.at = at;
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
