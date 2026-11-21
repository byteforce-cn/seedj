package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 登录响应。
 *
 * @param code 0 表示成功
 * @param userId IAM 主体标识，JSON 字段 {@code user_id}
 * @param displayName 展示名，来自 JWT claim，JSON 字段 {@code display_name}
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LoginResp(int code, String userId, String displayName) {}
