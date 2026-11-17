package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 登录请求。
 *
 * @param accessToken IAM 签发的访问令牌，JSON 字段 {@code access_token}
 * @param deviceId 设备标识，JSON 字段 {@code device_id}；缺省时服务端按连接生成
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LoginReq(String accessToken, String deviceId) {}
