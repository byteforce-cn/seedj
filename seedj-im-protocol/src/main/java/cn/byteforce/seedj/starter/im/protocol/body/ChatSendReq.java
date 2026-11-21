package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 发送请求：{@code conversation_id} 与 {@code peer_id} 至少给一个。
 *
 * @param clientMsgId 客户端幂等键；重发返回同一条消息
 * @param conversationId 会话标识；已知会话时使用
 * @param peerId 对端 subject；单聊首条消息用它建会话
 * @param kind 内容类型：{@code TEXT} / {@code IMAGE} / {@code FILE}
 * @param body 消息体
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatSendReq(
        String clientMsgId,
        String conversationId,
        String peerId,
        String kind,
        Map<String, Object> body) {

    public ChatSendReq {
        body = body == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(body));
    }
}
