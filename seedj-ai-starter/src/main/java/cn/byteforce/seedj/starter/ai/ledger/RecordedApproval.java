package cn.byteforce.seedj.starter.ai.ledger;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.DecisionRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从已落账的 {@code approval} 记录体里读回审批快照。
 *
 * <p>审批状态只认账本：工作流实例可能被删除、可能不可用，但账本里的 {@code APPROVAL} 记录一旦写入就不会改。grant 与守卫都按这条记录判断「是否已批准、绑定的是哪组参数」。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class RecordedApproval {

    private RecordedApproval() {}

    /**
     * 读回一条 {@code approval} 记录。
     *
     * @param record 账本记录
     * @return 审批快照
     * @throws NullPointerException {@code record} 为 {@code null}
     * @throws IllegalArgumentException 记录不是 {@code approval}
     * @throws IllegalStateException 记录体缺必填字段
     */
    public static ApprovalSnapshot of(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        if (!"approval".equals(record.body().get("record_kind"))) {
            throw new IllegalArgumentException("record is not an approval: " + record.recordId());
        }
        Object value = record.body().get("approval");
        if (!(value instanceof Map<?, ?> fields)) {
            throw new IllegalStateException("approval 记录缺少 approval: " + record.recordId());
        }
        return new ApprovalSnapshot(
                text(fields.get("instance_id"), "instance_id", record),
                text(fields.get("task_id"), "task_id", record),
                status(fields.get("status"), record),
                instant(fields.get("requested_at")),
                uuid(fields.get("approver")),
                instant(fields.get("approved_at")),
                text(fields.get("args_hash"), "args_hash", record),
                revision(fields.get("instance_revision")));
    }

    private static ApprovalStatus status(Object value, DecisionRecord record) {
        try {
            return ApprovalStatus.fromWire(String.valueOf(value));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "approval 记录含未知 status: " + value + " (" + record.recordId() + ")", error);
        }
    }

    private static String text(Object value, String field, DecisionRecord record) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("approval 记录缺少 " + field + ": " + record.recordId());
        }
        return text;
    }

    private static Instant instant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static Long revision(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(value.toString());
    }
}
