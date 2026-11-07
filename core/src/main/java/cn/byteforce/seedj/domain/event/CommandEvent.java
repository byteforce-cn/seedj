package cn.byteforce.seedj.domain.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 命令产生的持久化事件（事件契约的元结构）。
 *
 * <p>与 {@link DomainEvent} 的区别：{@code DomainEvent} 是业务事件本体； 本类型带 {@code
 * occurred_at/recorded_at/actor/subjects/idempotency_key} 等线格式字段，用于 outbox 与审计摘要复算。时间一律 UTC、微秒精度， 且
 * {@code occurred_at} 不得晚于 {@code recorded_at}（T-R010）。
 *
 * @param id 事件 ID（UUIDv7）
 * @param type 事件类型
 * @param occurredAt 业务发生时间
 * @param recordedAt 系统记录时间
 * @param actor 发起者
 * @param subjects 涉及的业务对象 ID
 * @param payload 事件载荷
 * @param idempotencyKey 幂等键（{@code command_id:rule_id:action_index}）
 * @param version 事件 Schema 版本
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandEvent(
        UUID id,
        String type,
        Instant occurredAt,
        Instant recordedAt,
        UUID actor,
        List<UUID> subjects,
        Map<String, Object> payload,
        String idempotencyKey,
        int version) {

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9_.]*$");

    public CommandEvent {
        Objects.requireNonNull(id, "id");
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("illegal event type: " + type);
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(payload, "payload");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        recordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS);
        if (occurredAt.isAfter(recordedAt)) {
            throw new IllegalArgumentException("occurredAt must not be after recordedAt (T-R010)");
        }
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
