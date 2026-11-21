package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 卡片动作广播：让同会话其它端刷新卡片。
 *
 * @param messageId 卡片消息标识
 * @param actionId 按钮标识
 * @param actorId 操作人
 * @param result 处理结果
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CardActionNotify(
        String messageId, String actionId, String actorId, Map<String, Object> result) {}
