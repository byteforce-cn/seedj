package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 在线投递与离线补拉的统一消息视图。
 *
 * @param messageId 消息标识；客户端据此去重
 * @param conversationId 会话标识
 * @param seq 全局序号
 * @param senderId 发送者 subject
 * @param kind 内容类型
 * @param body 消息体
 * @param ts 落库时间（epoch 毫秒）
 * @param editedAt 最后编辑时间（epoch 毫秒）；{@code null} 表示未编辑
 * @param recalledAt 撤回时间（epoch 毫秒）；{@code null} 表示未撤回。已撤回时 {@code body} 为空
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ChatDeliver(
        String messageId,
        String conversationId,
        long seq,
        String senderId,
        String kind,
        Map<String, Object> body,
        long ts,
        Long editedAt,
        Long recalledAt) {

    public ChatDeliver {
        body = body == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(body));
    }

    /**
     * 未编辑、未撤回的投递视图（向后兼容的 7 参构造）。
     *
     * @param messageId 消息标识
     * @param conversationId 会话标识
     * @param seq 全局序号
     * @param senderId 发送者 subject
     * @param kind 内容类型
     * @param body 消息体
     * @param ts 落库时间（epoch 毫秒）
     */
    public ChatDeliver(
            String messageId,
            String conversationId,
            long seq,
            String senderId,
            String kind,
            Map<String, Object> body,
            long ts) {
        this(messageId, conversationId, seq, senderId, kind, body, ts, null, null);
    }
}
