package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 撤回通知：以命令字 {@code RECALL_REQ}、{@code msgType=2} 广播给会话成员（含发送者其它端）。
 *
 * @param conversationId 会话标识
 * @param messageId 被撤回的消息标识
 * @param seq 消息序号；客户端据此定位
 * @param actorId 发起撤回的 subject
 * @param ts 撤回时间（epoch 毫秒）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RecallNotify(
        String conversationId, String messageId, long seq, String actorId, long ts) {}
