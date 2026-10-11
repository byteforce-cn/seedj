package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 领域事件（{@code 01-data-contract/core.ddl} event 表物化）。
 *
 * <p>append-only：事件是不可变更的事实；{@code occurred_at <= recorded_at}
 * （T-R010）。同一命令内事件与命令、审计共享同一时刻（参考内核语义）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisEvent {

    private final UUID id;

    private final String type;

    private final Instant occurredAt;

    private final Instant recordedAt;

    private final UUID actor;

    private final List<UUID> subjects;

    private final JsonNode payload;

    private final String idempotencyKey;

    private final int version;

    /**
     * 构造事件。
     *
     * @param id             事件 ID
     * @param type           事件类型
     * @param occurredAt     发生时刻
     * @param recordedAt     记录时刻
     * @param actor          操作人
     * @param subjects       主体列表
     * @param payload        载荷
     * @param idempotencyKey 事件幂等键
     * @param version        事件版本
     */
    public EisEvent(UUID id, String type, Instant occurredAt, Instant recordedAt, UUID actor,
                    List<UUID> subjects, JsonNode payload, String idempotencyKey, int version) {
        this.id = id;
        this.type = type;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.actor = actor;
        this.subjects = subjects == null ? List.of() : List.copyOf(subjects);
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.version = version;
    }

    /**
     * 获取事件 ID。
     *
     * @return 事件 ID
     */
    public UUID getId() {
        return id;
    }

    /**
     * 获取事件类型。
     *
     * @return 事件类型
     */
    public String getType() {
        return type;
    }

    /**
     * 获取发生时刻。
     *
     * @return occurred_at
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * 获取记录时刻。
     *
     * @return recorded_at
     */
    public Instant getRecordedAt() {
        return recordedAt;
    }

    /**
     * 获取操作人。
     *
     * @return actor
     */
    public UUID getActor() {
        return actor;
    }

    /**
     * 获取主体列表。
     *
     * @return subjects
     */
    public List<UUID> getSubjects() {
        return subjects;
    }

    /**
     * 获取载荷。
     *
     * @return payload
     */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * 获取幂等键。
     *
     * @return 事件幂等键
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 获取版本。
     *
     * @return 事件版本
     */
    public int getVersion() {
        return version;
    }
}
