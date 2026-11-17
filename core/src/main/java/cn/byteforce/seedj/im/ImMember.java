package cn.byteforce.seedj.im;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一段成员任期：谁在什么序号区间内属于某个会话。
 *
 * <p>一行是一个任期，退出写 {@link #leftSeq()}，再次入群是新增一行——反复进出保留全部历史， 因此「seq=X 时谁在群里」可以用一次区间比较回答，不需要版本链。
 *
 * @param conversationId 会话标识
 * @param subjectId IAM subject
 * @param joinedSeq 入群序号（全局单调序列）
 * @param leftSeq 退群序号；{@code null} 表示仍在群里
 * @param role 角色
 * @param mutedUntil 免打扰截止时间；{@code null} 表示不静音
 * @param joinedAt 入群时间
 * @author ByteForce
 * @since 1.0.0
 */
public record ImMember(
        UUID conversationId,
        String subjectId,
        long joinedSeq,
        Long leftSeq,
        ImMemberRole role,
        Instant mutedUntil,
        Instant joinedAt) {

    public ImMember {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        if (joinedSeq <= 0) {
            throw new IllegalArgumentException("joinedSeq must be positive");
        }
        if (leftSeq != null && leftSeq <= joinedSeq) {
            throw new IllegalArgumentException("leftSeq must be greater than joinedSeq");
        }
    }

    /** 该任期是否仍然有效（未退群）。 */
    public boolean active() {
        return leftSeq == null;
    }

    /**
     * 该任期在给定序号时是否有效。
     *
     * @param seq 查询序号
     * @return 有效为 {@code true}
     */
    public boolean activeAt(long seq) {
        return joinedSeq <= seq && (leftSeq == null || leftSeq > seq);
    }
}
