package cn.byteforce.seedj.starter.eis.core;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 命令存储（JDBC）：{@code command} 契约表 + 部署侧辅助表（{@code eis_idempotency} /
 * {@code eis_command_event} / {@code eis_command_rejection}）。
 *
 * <p>契约表与辅助表的分工（EIS 实现口径，不改契约文件）：</p>
 * <ul>
 *   <li>{@code command}：命令意图与终态（契约列原样）；</li>
 *   <li>{@code eis_idempotency}：幂等键 → 指纹（SHA-256）+ 命令 ID——
 *       弥补契约表无指纹列；</li>
 *   <li>{@code eis_command_event}：命令 → 事件关联（含顺序位），弥补契约表无
 *       command_id 关联；</li>
 *   <li>{@code eis_command_rejection}：拒绝错误码——契约表仅有 rejection_reason，
 *       而 {@code GET /commands/{id}} 需回传 {@code code}；</li>
 *   <li>{@code eis_command_created}：受理结果的主对象指针（{@code created}）——
 *       契约表无该列，而幂等重放/冲突比对/终端查询需回传首次结果的 {@code created}。</li>
 * </ul>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisCommandStore {

    private final JdbcTemplate jdbc;

    /**
     * 构造命令存储。
     *
     * @param jdbc JDBC 模板（参与外层事务）
     */
    public EisCommandStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询幂等记录。
     *
     * @param idempotencyKey 幂等键
     * @return 记录；不存在为空
     */
    public Optional<EisIdempotencyRecord> findIdempotency(String idempotencyKey) {
        List<EisIdempotencyRecord> rows = jdbc.query(
                "SELECT fingerprint, command_id FROM eis_idempotency WHERE idempotency_key = ?",
                (rs, rowNum) -> new EisIdempotencyRecord(rs.getString(1), rs.getObject(2, UUID.class)),
                idempotencyKey);
        return rows.stream().findFirst();
    }

    /**
     * 幂等键占位（并发安全）：{@code ON CONFLICT DO NOTHING}。
     *
     * @param idempotencyKey 幂等键
     * @param fingerprint    内容指纹
     * @param commandId      命令 ID
     * @return 占位成功为 {@code true}；已存在（含并发未提交等待后可见）为 {@code false}
     */
    public boolean insertIdempotencyIfAbsent(String idempotencyKey, String fingerprint, UUID commandId) {
        int updated = jdbc.update("INSERT INTO eis_idempotency (idempotency_key, fingerprint, command_id)"
                        + " VALUES (?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                idempotencyKey, fingerprint, commandId);
        return updated > 0;
    }

    /**
     * 写入命令（pending 态；payload 为脱敏后的落库视图）。
     *
     * @param commandId      命令 ID
     * @param type           命令类型
     * @param issuedAt       签发时刻
     * @param issuer         操作人
     * @param targets        作用对象
     * @param payload        落库载荷
     * @param idempotencyKey 幂等键
     */
    public void insertCommand(UUID commandId, String type, Instant issuedAt, UUID issuer, List<UUID> targets,
                              JsonNode payload, String idempotencyKey) {
        String targetsLiteral = toArrayLiteral(targets);
        jdbc.update("INSERT INTO command (id, type, issued_at, issuer, targets, payload, idempotency_key, status)"
                        + " VALUES (?, ?, ?, ?, CAST(? AS uuid[]), CAST(? AS jsonb), ?, 'pending')",
                commandId, type, OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC), issuer, targetsLiteral,
                payload == null ? "{}" : payload.toString(), idempotencyKey);
    }

    /**
     * 标记命令已接受并登记事件关联（保序）。
     *
     * @param commandId 命令 ID
     * @param eventIds  事件 ID 列表
     */
    public void markAccepted(UUID commandId, List<UUID> eventIds) {
        markAccepted(commandId, eventIds, null);
    }

    /**
     * 标记命令已接受、登记事件关联（保序）并持久化主对象指针。
     *
     * @param commandId 命令 ID
     * @param eventIds  事件 ID 列表
     * @param created   主对象指针（可空；供幂等重放与终态查询回传）
     */
    public void markAccepted(UUID commandId, List<UUID> eventIds, EisObjectRef created) {
        jdbc.update("UPDATE command SET status = 'accepted' WHERE id = ?", commandId);
        int position = 0;
        for (UUID eventId : eventIds) {
            jdbc.update("INSERT INTO eis_command_event (command_id, event_id, position) VALUES (?, ?, ?)",
                    commandId, eventId, position++);
        }
        if (created != null) {
            jdbc.update("INSERT INTO eis_command_created (command_id, object_type, object_id)"
                            + " VALUES (?, ?, ?)",
                    commandId, created.getObjectType(), created.getId());
        }
    }

    /**
     * 标记命令已拒绝（原因 + 错误码）。
     *
     * @param commandId 命令 ID
     * @param code      错误码
     * @param reason    原因
     */
    public void markRejected(UUID commandId, String code, String reason) {
        jdbc.update("UPDATE command SET status = 'rejected', rejection_reason = ? WHERE id = ?", reason, commandId);
        jdbc.update("INSERT INTO eis_command_rejection (command_id, rejection_code) VALUES (?, ?)",
                commandId, code);
    }

    /**
     * 查询命令终态（含事件关联与拒绝码）。
     *
     * @param commandId 命令 ID
     * @return 命令记录；不存在为空
     */
    public Optional<EisStoredCommand> findCommand(UUID commandId) {
        List<EisStoredCommand> rows = jdbc.query(
                "SELECT id, type, issued_at, issuer, status, rejection_reason FROM command WHERE id = ?",
                (rs, rowNum) -> new EisStoredCommand(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), null, null, null),
                commandId);
        Optional<EisStoredCommand> found = rows.stream().findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EisStoredCommand command = found.get();
        List<UUID> eventIds = jdbc.query("SELECT event_id FROM eis_command_event WHERE command_id = ? ORDER BY position",
                (rs, rowNum) -> rs.getObject(1, UUID.class), commandId);
        String rejectionCode = jdbc.query("SELECT rejection_code FROM eis_command_rejection WHERE command_id = ?",
                (rs, rowNum) -> rs.getString(1), commandId).stream().findFirst().orElse(null);
        EisObjectRef created = jdbc.query(
                        "SELECT object_type, object_id FROM eis_command_created WHERE command_id = ?",
                        (rs, rowNum) -> new EisObjectRef(rs.getString(1), rs.getObject(2, UUID.class)), commandId)
                .stream().findFirst().orElse(null);
        return Optional.of(new EisStoredCommand(command.getId(), command.getType(), command.getIssuedAt(),
                command.getIssuer(), command.getStatus(), command.getRejectionReason(), rejectionCode, eventIds,
                created));
    }

    private static String toArrayLiteral(List<UUID> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.append('}').toString();
    }

    /**
     * 幂等记录（键 → 指纹 + 命令 ID）。
     */
    public static final class EisIdempotencyRecord {

        private final String fingerprint;

        private final UUID commandId;

        EisIdempotencyRecord(String fingerprint, UUID commandId) {
            this.fingerprint = fingerprint;
            this.commandId = commandId;
        }

        /**
         * 获取内容指纹。
         *
         * @return SHA-256 指纹
         */
        public String getFingerprint() {
            return fingerprint;
        }

        /**
         * 获取命令 ID。
         *
         * @return 首次提交的命令 ID
         */
        public UUID getCommandId() {
            return commandId;
        }
    }

    /**
     * 命令终态视图（含事件关联与拒绝码）。
     */
    public static final class EisStoredCommand {

        private final UUID id;

        private final String type;

        private final Instant issuedAt;

        private final UUID issuer;

        private final String status;

        private final String rejectionReason;

        private final String rejectionCode;

        private final List<UUID> eventIds;

        private final EisObjectRef created;

        /**
         * 构造命令终态视图（无主对象指针）。
         *
         * @param id              命令 ID
         * @param type            命令类型
         * @param issuedAt        签发时刻
         * @param issuer          操作人
         * @param status          状态
         * @param rejectionReason 拒绝原因（可空）
         * @param rejectionCode   拒绝错误码（可空）
         * @param eventIds        事件 ID 列表（保序，可空）
         */
        public EisStoredCommand(UUID id, String type, Instant issuedAt, UUID issuer, String status,
                                String rejectionReason, String rejectionCode, List<UUID> eventIds) {
            this(id, type, issuedAt, issuer, status, rejectionReason, rejectionCode, eventIds, null);
        }

        /**
         * 构造命令终态视图（含主对象指针）。
         *
         * @param id              命令 ID
         * @param type            命令类型
         * @param issuedAt        签发时刻
         * @param issuer          操作人
         * @param status          状态
         * @param rejectionReason 拒绝原因（可空）
         * @param rejectionCode   拒绝错误码（可空）
         * @param eventIds        事件 ID 列表（保序，可空）
         * @param created         主对象指针（可空）
         */
        public EisStoredCommand(UUID id, String type, Instant issuedAt, UUID issuer, String status,
                                String rejectionReason, String rejectionCode, List<UUID> eventIds,
                                EisObjectRef created) {
            this.id = id;
            this.type = type;
            this.issuedAt = issuedAt;
            this.issuer = issuer;
            this.status = status;
            this.rejectionReason = rejectionReason;
            this.rejectionCode = rejectionCode;
            this.eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
            this.created = created;
        }

        /**
         * 获取命令 ID。
         *
         * @return 命令 ID
         */
        public UUID getId() {
            return id;
        }

        /**
         * 获取命令类型。
         *
         * @return 命令类型
         */
        public String getType() {
            return type;
        }

        /**
         * 获取签发时刻。
         *
         * @return issued_at
         */
        public Instant getIssuedAt() {
            return issuedAt;
        }

        /**
         * 获取操作人。
         *
         * @return 操作人 UUID
         */
        public UUID getIssuer() {
            return issuer;
        }

        /**
         * 获取状态。
         *
         * @return pending / accepted / rejected
         */
        public String getStatus() {
            return status;
        }

        /**
         * 获取拒绝原因。
         *
         * @return 原因；未拒绝为 {@code null}
         */
        public String getRejectionReason() {
            return rejectionReason;
        }

        /**
         * 获取拒绝错误码。
         *
         * @return 错误码；未拒绝为 {@code null}
         */
        public String getRejectionCode() {
            return rejectionCode;
        }

        /**
         * 获取事件 ID 列表（保序）。
         *
         * @return 事件 ID 列表
         */
        public List<UUID> getEventIds() {
            return eventIds;
        }

        /**
         * 获取主对象指针。
         *
         * @return 对象引用；命令无主对象指针时为 {@code null}
         */
        public EisObjectRef getCreated() {
            return created;
        }
    }
}
