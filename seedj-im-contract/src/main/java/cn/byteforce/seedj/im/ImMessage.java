package cn.byteforce.seedj.im;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条已落库的消息。
 *
 * <p>消息是只追加的：内容不可变，编辑与撤回是独立事实（{@link #editedAt()} / {@link #recalledAt()}）。 {@link #seq()}
 * 来自全局单调序列，因此会话内可能不连续——客户端必须容忍缺号，按 {@code seq} 排序、 按 {@link #id()} 去重。
 *
 * @param id 消息标识
 * @param tenantId 租户
 * @param conversationId 会话标识
 * @param seq 全局单调序号
 * @param senderId 发送者的 IAM subject
 * @param kind 内容类型
 * @param body 消息体（结构化 JSON）；只读
 * @param clientMsgId 客户端幂等键；{@code null} 表示客户端未提供
 * @param createdAt 落库时间
 * @param editedAt 编辑时间；{@code null} 表示未编辑
 * @param recalledAt 撤回时间；{@code null} 表示未撤回
 * @author ByteForce
 * @since 1.0.0
 */
public record ImMessage(
        UUID id,
        String tenantId,
        UUID conversationId,
        long seq,
        String senderId,
        ImMessageKind kind,
        Map<String, Object> body,
        String clientMsgId,
        Instant createdAt,
        Instant editedAt,
        Instant recalledAt) {

    public ImMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        if (body == null || body.isEmpty()) {
            body = Map.of();
        } else {
            body = Collections.unmodifiableMap(new LinkedHashMap<>(body));
        }
        clientMsgId = clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId;
    }
}
