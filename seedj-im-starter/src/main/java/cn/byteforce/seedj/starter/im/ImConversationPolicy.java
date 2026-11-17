package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.im.ImConversation;

/**
 * 会话治理策略：谁能建群、谁能在群里做什么。
 *
 * <p>默认实现是「同租户放行」，宿主覆盖这个 bean 即可接入自己的组织模型；starter 的默认实现只 兜底，不替业务做决定。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ImConversationPolicy {

    /**
     * 是否允许建群。
     *
     * @param caller 调用方
     * @return 允许为 {@code true}
     */
    default boolean canCreateGroup(Caller caller) {
        return true;
    }

    /**
     * 群成员上限。
     *
     * @return 上限；非正值表示不限制
     */
    default int maxMembers() {
        return 500;
    }

    /**
     * 是否允许把某人加入会话。
     *
     * @param caller 调用方
     * @param conversation 会话
     * @param subjectId 被加入的 subject
     * @return 允许为 {@code true}
     */
    default boolean canAddMember(Caller caller, ImConversation conversation, String subjectId) {
        return true;
    }

    /**
     * 是否允许把某人移出会话（或本人退群）。
     *
     * @param caller 调用方
     * @param conversation 会话
     * @param subjectId 被移出的 subject
     * @return 允许为 {@code true}
     */
    default boolean canRemoveMember(Caller caller, ImConversation conversation, String subjectId) {
        return true;
    }

    /**
     * 是否允许在会话里发送。
     *
     * @param caller 调用方
     * @param conversation 会话
     * @return 允许为 {@code true}
     */
    default boolean canSend(Caller caller, ImConversation conversation) {
        return true;
    }

    /**
     * 是否允许改群名。
     *
     * @param caller 调用方
     * @param conversation 会话
     * @return 允许为 {@code true}
     */
    default boolean canRename(Caller caller, ImConversation conversation) {
        return true;
    }
}
