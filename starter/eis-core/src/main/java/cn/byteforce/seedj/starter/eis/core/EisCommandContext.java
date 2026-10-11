package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 命令受理上下文：处理器与规则访问命令数据、对象存储与时钟的唯一入口。
 *
 * <p>同一命令内 {@link #getNow()} 固定不变：命令 issued_at、事件 occurred_at /
 * recorded_at、对象封口时间与审计时间戳共享同一时刻（与参考内核一致）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandContext {

    private final UUID commandId;

    private final String commandType;

    private final UUID actor;

    private final JsonNode payload;

    private final Instant now;

    private final EisClock clock;

    private final EisObjectRepository objects;

    private final EisObjectTypeRegistry objectTypes;

    /**
     * 构造上下文（运行时内部使用）。
     *
     * @param commandId   命令 ID
     * @param commandType 命令类型
     * @param actor       操作人
     * @param payload     命令载荷
     * @param now         命令时刻
     * @param clock       EIS 时钟
     * @param objects     对象存储
     * @param objectTypes 对象类型注册表
     */
    public EisCommandContext(UUID commandId, String commandType, UUID actor, JsonNode payload, Instant now,
                             EisClock clock, EisObjectRepository objects, EisObjectTypeRegistry objectTypes) {
        this.commandId = commandId;
        this.commandType = commandType;
        this.actor = actor;
        this.payload = payload;
        this.now = now;
        this.clock = clock;
        this.objects = objects;
        this.objectTypes = objectTypes;
    }

    /**
     * 生成新对象/事件 ID（UUIDv7，时间有序）。
     *
     * @return 新 ID
     */
    public UUID newId() {
        return UuidV7.generate(clock);
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
     * 获取命令类型。
     *
     * @return 命令类型
     */
    public String getCommandType() {
        return commandType;
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
     * 获取命令载荷。
     *
     * @return 载荷 JSON
     */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * 获取命令时刻。
     *
     * @return 统一命令时刻
     */
    public Instant getNow() {
        return now;
    }

    /**
     * 获取对象存储。
     *
     * @return 对象存储
     */
    public EisObjectRepository getObjects() {
        return objects;
    }

    /**
     * 按名取对象类型（未注册时为 {@code null}）。
     *
     * @param objectType 对象类型名
     * @return 对象类型；未注册为 {@code null}
     */
    public EisObjectType getObjectType(String objectType) {
        return objectTypes.get(objectType);
    }
}
