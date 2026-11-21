package cn.byteforce.seedj.starter.im.protocol.body;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 成员列表响应。
 *
 * @param conversationId 会话标识
 * @param members 成员列表
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MemberListResp(String conversationId, List<MemberView> members) {

    public MemberListResp {
        members = members == null ? List.of() : List.copyOf(members);
    }

    /**
     * 一个成员的展示视图。
     *
     * @param subjectId IAM subject
     * @param role 角色
     * @param joinedSeq 入群序号
     * @param joinedAt 入群时间（epoch 毫秒）
     * @param displayName 展示名；由宿主覆盖 {@code ImDisplayNameResolver} 决定
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemberView(
            String subjectId, String role, long joinedSeq, long joinedAt, String displayName) {}
}
