package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 编辑通知：以命令字 {@code EDIT_REQ}、{@code msgType=2} 广播给会话成员（含发送者其它端）。
 *
 * @param conversationId 会话标识
 * @param messageId 被编辑的消息标识
 * @param seq 消息序号
 * @param senderId 发送者 subject
 * @param body 新消息体
 * @param editedAt 编辑时间（epoch 毫秒）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EditNotify(
        String conversationId,
        String messageId,
        long seq,
        String senderId,
        Map<String, Object> body,
        long editedAt) {

    public EditNotify {
        body = body == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(body));
    }
}
