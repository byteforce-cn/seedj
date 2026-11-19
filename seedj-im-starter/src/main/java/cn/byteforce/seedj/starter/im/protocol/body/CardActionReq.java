package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 卡片动作请求。
 *
 * @param messageId 卡片消息标识
 * @param actionId 按钮标识
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CardActionReq(String messageId, String actionId) {}
