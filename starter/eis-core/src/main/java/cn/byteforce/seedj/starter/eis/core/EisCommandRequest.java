package cn.byteforce.seedj.starter.eis.core;

import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 命令提交请求（运行时入口的规范化载体）。
 *
 * <p>字段与 {@code CommandRequest} 契约一致：{@code type} / {@code payload} /
 * {@code idempotency_key} / {@code targets}；操作人（actor）由提交方显式给出
 * （HTTP 面从已认证主体解析；内部命令由服务端指定）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EisCommandRequest {

    private final String commandType;

    private final JsonNode payload;

    private final String idempotencyKey;

    private final List<UUID> targets;

    private final UUID actor;

    /**
     * 构造命令请求。
     *
     * @param commandType    命令类型（如 {@code iam.user.create}）
     * @param payload        命令载荷（JSON 对象）
     * @param idempotencyKey 幂等键（非空）
     * @param targets        作用对象 ID 列表（可空）
     * @param actor          操作人 UUID
     */
    public EisCommandRequest(String commandType, JsonNode payload, String idempotencyKey,
                             List<UUID> targets, UUID actor) {
        this.commandType = commandType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.actor = actor;
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
     * 获取命令载荷。
     *
     * @return 载荷 JSON
     */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * 获取幂等键。
     *
     * @return 幂等键
     */
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    /**
     * 获取作用对象列表。
     *
     * @return 作用对象 UUID 列表（不可变、可为空列表）
     */
    public List<UUID> getTargets() {
        return Collections.unmodifiableList(targets);
    }

    /**
     * 获取操作人。
     *
     * @return 操作人 UUID
     */
    public UUID getActor() {
        return actor;
    }
}
