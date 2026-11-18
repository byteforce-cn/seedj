package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.EffectStatus;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 从已落账记录体里读回的执行结局。
 *
 * <p>上报幂等复用的那条记录、对账要不要补链校验，判断依据都只能是账本里写着的东西，不能是调用方这一轮说的话：
 * 于是两处都先读回记录体的 {@code effect} 再决定。只取三样——{@code status}（还能不能补链校验）、
 * {@code audit_entry_id}（是否已经链校验过）、{@code command_id}（拿哪条命令去查链条目）。
 *
 * <p>记录体格式由 {@link cn.byteforce.seedj.ai.DecisionRecordBodies} 唯一定义：{@code effect} 是嵌套对象， null
 * 字段省略。
 *
 * @param status 结局状态
 * @param auditEntryId 审计链条目标识；{@code null} 表示未链校验
 * @param commandId 命令记录标识；{@code null} 表示没有命令
 * @author ByteForce
 * @since 1.0.0
 */
record RecordedEffect(EffectStatus status, String auditEntryId, UUID commandId) {

    /**
     * 读回一条 {@code outcome} / {@code reconciled} 记录的结局。
     *
     * @param record 账本记录
     * @return 记录体里的结局
     * @throws NullPointerException {@code record} 为 {@code null}
     * @throws IllegalStateException 记录体缺 {@code effect} 或缺 {@code effect.status}
     */
    static RecordedEffect of(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        Object effect = record.body().get("effect");
        if (!(effect instanceof Map<?, ?> fields)) {
            throw new IllegalStateException(
                    record.kind().wire() + " 记录缺少 effect: " + record.recordId());
        }
        Object status = fields.get("status");
        if (!(status instanceof String wire)) {
            throw new IllegalStateException("effect 缺少 status: " + record.recordId());
        }
        return new RecordedEffect(
                EffectStatus.fromWire(wire),
                text(fields.get("audit_entry_id")),
                uuid(fields.get("command_id")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
