package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 撤回请求：发送者撤回自己的消息，群主/管理员可撤回群内消息。
 *
 * @param conversationId 会话标识
 * @param messageId 要撤回的消息标识
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RecallReq(String conversationId, String messageId) {}
