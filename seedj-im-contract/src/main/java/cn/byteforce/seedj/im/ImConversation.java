package cn.byteforce.seedj.im;

import cn.byteforce.seedj.core.security.Caller;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条 IM 会话的快照。
 *
 * <p>会话不是双时间轴对象：它只回答「现在是什么样」，时点问题由成员任期 （{@link ImMember#joinedSeq()} / {@link
 * ImMember#leftSeq()}）回答，不需要版本链。
 *
 * @param id 会话标识
 * @param tenantId 租户；空白回落 {@link Caller#DEFAULT_TENANT}
 * @param kind 会话类型
 * @param title 群名；单聊为 {@code null}
 * @param ownerId 群主，或单聊的发起者（IAM subject）
 * @param directKey 单聊去重键；群聊为 {@code null}
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间；{@code null} 表示与创建时间相同
 * @author ByteForce
 * @since 1.0.0
 */
public record ImConversation(
        UUID id,
        String tenantId,
        ImConversationKind kind,
        String title,
        String ownerId,
        String directKey,
        Instant createdAt,
        Instant updatedAt) {

    public ImConversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
        tenantId = tenantId == null || tenantId.isBlank() ? Caller.DEFAULT_TENANT : tenantId;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        title = title == null || title.isBlank() ? null : title;
        directKey = directKey == null || directKey.isBlank() ? null : directKey;
        if (kind == ImConversationKind.SINGLE && directKey == null) {
            throw new IllegalArgumentException("single conversation requires a direct key");
        }
    }

    /**
     * 单聊去重键：两个 subject 排序后用 {@code |} 拼接。
     *
     * <p>排序是唯一规范化的地方——A→B 与 B→A 必须得到同一个键，否则会建出两条会话。
     *
     * @param left 一方 subject
     * @param right 另一方 subject
     * @return 去重键
     */
    public static String directKey(String left, String right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.isBlank() || right.isBlank()) {
            throw new IllegalArgumentException("direct key requires two non-blank subjects");
        }
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }
}
