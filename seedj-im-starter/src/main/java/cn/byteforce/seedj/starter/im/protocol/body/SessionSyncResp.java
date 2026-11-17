package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 会话同步响应：会话快照 + 已读游标 + 建议的补拉游标。
 *
 * @param conversations 会话视图，按最近更新倒序
 * @param readCursors 已读游标
 * @param nextCursor 该设备应从此序号之后继续补拉
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SessionSyncResp(
        List<ConversationView> conversations, List<ReadCursorView> readCursors, long nextCursor) {

    public SessionSyncResp {
        conversations = conversations == null ? List.of() : List.copyOf(conversations);
        readCursors = readCursors == null ? List.of() : List.copyOf(readCursors);
    }

    /**
     * 一条会话的当前视图。
     *
     * @param conversationId 会话标识
     * @param kind 会话类型
     * @param title 群名；单聊为空
     * @param ownerId 群主 / 单聊发起者
     * @param lastSeq 会话内最大序号；无消息为 0
     * @param unread 未读数（{@code lastSeq - lastReadSeq} 的条数）
     * @param updatedAt 最后更新时间（epoch 毫秒）
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConversationView(
            String conversationId,
            String kind,
            String title,
            String ownerId,
            long lastSeq,
            long unread,
            long updatedAt) {}

    /**
     * 一条已读游标。
     *
     * @param conversationId 会话标识
     * @param lastReadSeq 已读到的序号
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ReadCursorView(String conversationId, long lastReadSeq) {}
}
