package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 卡片动作响应。
 *
 * @param messageId 卡片消息标识
 * @param actionId 按钮标识
 * @param result 处理结果
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CardActionResp(String messageId, String actionId, Map<String, Object> result) {}
