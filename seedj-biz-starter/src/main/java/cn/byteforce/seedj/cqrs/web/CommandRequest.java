package cn.byteforce.seedj.cqrs.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 命令提交请求体（线格式使用 snake_case）。
 *
 * @param type           命令类型
 * @param payload        载荷
 * @param idempotencyKey 幂等键
 * @param targets        目标对象
 * @author ByteForce
 * @since 1.0.0
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommandRequest(String type, Map<String, Object> payload, String idempotencyKey,
                             List<UUID> targets) {
}
