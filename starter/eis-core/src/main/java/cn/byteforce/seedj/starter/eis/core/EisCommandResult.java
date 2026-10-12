package cn.byteforce.seedj.starter.eis.core;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 命令受理结果（POST /commands 的响应载体；对齐 {@code CommandAccepted} /
 * {@code CommandRejected} / 幂等冲突三类契约形状）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandResult {

    /** 冲突状态字面量（契约外的内核扩展态：同幂等键不同内容）。 */
    public static final String STATUS_CONFLICT = "conflict";

    private final String status;

    private final UUID commandId;

    private final List<UUID> eventIds;

    private final String code;

    private final String reason;

    private final String reasonCode;

    private final boolean replayed;

    private final EisObjectRef created;

    private EisCommandResult(String status, UUID commandId, List<UUID> eventIds, String code,
                             String reason, String reasonCode, boolean replayed, EisObjectRef created) {
        this.status = status;
        this.commandId = commandId;
        this.eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        this.code = code;
        this.reason = reason;
        this.reasonCode = reasonCode;
        this.replayed = replayed;
        this.created = created;
    }

    /**
     * 接受结果。
     *
     * @param commandId 命令 ID
     * @param eventIds  产生的事件 ID 列表
     * @param created   主对象指针（可空）
     * @return 结果
     */
    public static EisCommandResult accepted(UUID commandId, List<UUID> eventIds, EisObjectRef created) {
        return new EisCommandResult(EisCommandStatus.ACCEPTED.getCode(), commandId, eventIds, null, null,
                null, false, created);
    }

    /**
     * 幂等重放结果（返回原终态）。
     *
     * @param status    原终态（accepted / rejected）
     * @param commandId 原命令 ID
     * @param eventIds  原事件列表（accepted 时）
     * @param code      原错误码（rejected 时）
     * @param reason    原原因（rejected 时）
     * @param reasonCode 原原因码（rejected 时）
     * @return 结果
     */
    public static EisCommandResult replay(String status, UUID commandId, List<UUID> eventIds,
                                          String code, String reason, String reasonCode) {
        return replay(status, commandId, eventIds, code, reason, reasonCode, null);
    }

    /**
     * 幂等重放结果（返回原终态与原主对象指针）。
     *
     * @param status    原终态（accepted / rejected）
     * @param commandId 原命令 ID
     * @param eventIds  原事件列表（accepted 时）
     * @param code      原错误码（rejected 时）
     * @param reason    原原因（rejected 时）
     * @param reasonCode 原原因码（rejected 时）
     * @param created   原主对象指针（可空）
     * @return 结果
     */
    public static EisCommandResult replay(String status, UUID commandId, List<UUID> eventIds,
                                          String code, String reason, String reasonCode,
                                          EisObjectRef created) {
        return new EisCommandResult(status, commandId, eventIds, code, reason, reasonCode, true, created);
    }

    /**
     * 拒绝结果。
     *
     * @param commandId  命令 ID
     * @param code       错误码
     * @param reason     原因
     * @param reasonCode 原因码（可空）
     * @return 结果
     */
    public static EisCommandResult rejected(UUID commandId, String code, String reason, String reasonCode) {
        return new EisCommandResult(EisCommandStatus.REJECTED.getCode(), commandId, List.of(), code, reason,
                reasonCode, false, null);
    }

    /**
     * 幂等冲突结果（EIS-409-001）：同键不同内容，保留原命令 ID。
     *
     * @param originalCommandId 首次提交的命令 ID
     * @return 结果
     */
    public static EisCommandResult conflict(UUID originalCommandId) {
        return new EisCommandResult(STATUS_CONFLICT, originalCommandId, List.of(), "EIS-409-001",
                "幂等键冲突：内容与首次提交不一致", null, false, null);
    }

    /**
     * 获取状态。
     *
     * @return accepted / rejected / conflict
     */
    public String getStatus() {
        return status;
    }

    /**
     * 获取命令 ID。
     *
     * @return 命令 ID
     */
    public UUID getCommandId() {
        return commandId;
    }

    /**
     * 获取事件 ID 列表。
     *
     * @return 事件 ID 列表
     */
    public List<UUID> getEventIds() {
        return Collections.unmodifiableList(eventIds);
    }

    /**
     * 获取错误码。
     *
     * @return 错误码；接受时为 {@code null}
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取原因。
     *
     * @return 原因；接受时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取机器可读原因码。
     *
     * @return 原因码；无则为 {@code null}
     */
    public String getReasonCode() {
        return reasonCode;
    }

    /**
     * 是否为幂等重放。
     *
     * @return 重放为 {@code true}
     */
    public boolean isReplayed() {
        return replayed;
    }

    /**
     * 获取主对象指针。
     *
     * @return 对象引用；无主对象指针时为 {@code null}（重放已持久化指针则一并返回）
     */
    public EisObjectRef getCreated() {
        return created;
    }
}
