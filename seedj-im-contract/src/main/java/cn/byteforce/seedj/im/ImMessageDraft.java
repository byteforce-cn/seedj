package cn.byteforce.seedj.im;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 待写入的消息：序号由调用方先分配，落库时间由存储实现补。
 *
 * <p>把 {@code seq} 留在调用方是刻意的：序号必须与消息插入同事务分配，而分配器可能是数据库序列， 存储实现不应该替业务决定「什么时候取号」。
 *
 * @param id 消息标识
 * @param tenantId 租户
 * @param conversationId 会话标识
 * @param seq 已经分配好的全局序号
 * @param senderId 发送者的 IAM subject
 * @param kind 内容类型
 * @param body 消息体
 * @param clientMsgId 客户端幂等键
 * @author ByteForce
 * @since 1.0.0
 */
public record ImMessageDraft(
        UUID id,
        String tenantId,
        UUID conversationId,
        long seq,
        String senderId,
        ImMessageKind kind,
        Map<String, Object> body,
        String clientMsgId) {

    public ImMessageDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(kind, "kind");
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be positive");
        }
        body = body == null || body.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(body));
        clientMsgId = clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId;
    }
}
