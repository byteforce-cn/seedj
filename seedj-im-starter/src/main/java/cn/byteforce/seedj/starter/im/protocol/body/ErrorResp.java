package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 错误响应。
 *
 * @param code 错误码（401/501/…）
 * @param message 可读错误信息
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ErrorResp(int code, String message) {}
