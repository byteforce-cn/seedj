package cn.byteforce.seedj.cqrs;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 命令受理结果：命令契约与查询契约之间的唯一桥。
 *
 * <p>接受态只携带 {@code locator + version} 与事件 ID，不携带任何读模型投影；
 * 拒绝态携带冻结错误码与原因，不携带 locator/version。{@code PENDING} 用于
 * 重复提交命中仍在执行中的命令，调用方应按可重试处理。</p>
 *
 * @param commandId 命令 ID
 * @param status    命令状态
 * @param eventIds  产生的事件 ID
 * @param locator   写入的聚合根 ID（接受态，与 {@code targetRef} 二选一）
 * @param targetRef 事件溯源命令的聚合标识文本（接受态，与 {@code locator} 二选一）
 * @param version   对象版本（接受态）
 * @param code      拒绝码（拒绝态）
 * @param reason    拒绝原因（拒绝态）
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandReceipt(UUID commandId, CommandStatus status, List<UUID> eventIds,
                             UUID locator, String targetRef, Long version, String code,
                             String reason) {

    /**
     * 兼容既有装配：不使用事件溯源标识文本。
     *
     * @param commandId 命令 ID
     * @param status    状态
     * @param eventIds  事件 ID
     * @param locator   聚合根 ID
     * @param version   对象版本
     * @param code      拒绝码
     * @param reason    拒绝原因
     */
    public CommandReceipt(UUID commandId, CommandStatus status, List<UUID> eventIds,
                          UUID locator, Long version, String code, String reason) {
        this(commandId, status, eventIds, locator, null, version, code, reason);
    }

    public CommandReceipt {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(status, "status");
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        switch (status) {
            case PENDING -> {
                if (locator != null || targetRef != null || version != null || code != null
                        || reason != null) {
                    throw new IllegalArgumentException(
                            "pending receipt must not carry terminal fields");
                }
            }
            case ACCEPTED -> {
                if ((locator == null) == (targetRef == null) || version == null || version < 1) {
                    throw new IllegalArgumentException(
                            "accepted receipt requires exactly one of locator or target_ref"
                                    + " and version >= 1");
                }
                if (code != null || reason != null) {
                    throw new IllegalArgumentException(
                            "accepted receipt must not carry rejection fields");
                }
            }
            case REJECTED -> {
                if (locator != null || targetRef != null || version != null) {
                    throw new IllegalArgumentException(
                            "rejected receipt must not carry locator or version");
                }
                if (code == null || reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException(
                            "rejected receipt requires code and reason");
                }
            }
            default -> throw new IllegalArgumentException("unknown status: " + status);
        }
    }

    /**
     * 从持久化记录映射。
     *
     * @param record 命令记录
     * @return 受理结果
     */
    public static CommandReceipt from(CommandRecord record) {
        Objects.requireNonNull(record, "record");
        return new CommandReceipt(record.id(), record.status(), record.eventIds(),
                record.locator(), record.targetRef(), record.version(), record.rejectionCode(),
                record.rejectionReason());
    }
}
