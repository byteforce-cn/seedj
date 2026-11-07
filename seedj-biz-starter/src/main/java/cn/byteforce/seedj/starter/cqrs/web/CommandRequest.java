package cn.byteforce.seedj.starter.cqrs.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 命令提交请求体（线格式使用 snake_case）。
 *
 * @param type 命令类型
 * @param payload 载荷
 * @param idempotencyKey 幂等键
 * @param targets 目标对象
 * @param expectedVersion 期望对象版本；{@code null} 表示不校验
 * @author ByteForce
 * @since 1.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommandRequest(
        String type,
        Map<String, Object> payload,
        String idempotencyKey,
        List<UUID> targets,
        Long expectedVersion) {

    /**
     * 兼容旧请求体：不校验版本。
     *
     * @param type 命令类型
     * @param payload 载荷
     * @param idempotencyKey 幂等键
     * @param targets 目标对象
     */
    public CommandRequest(
            String type, Map<String, Object> payload, String idempotencyKey, List<UUID> targets) {
        this(type, payload, idempotencyKey, targets, null);
    }
}
