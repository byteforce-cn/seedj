package cn.byteforce.seedj.starter.im.protocol.body;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 发送响应。
 *
 * @param messageId 消息标识
 * @param conversationId 会话标识（首条消息自动建会话时由服务端补全）
 * @param seq 全局序号
 * @param ts 落库时间（epoch 毫秒）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatSendResp(String messageId, String conversationId, long seq, long ts) {}
