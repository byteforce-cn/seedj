package cn.byteforce.seedj.im;

/**
 * 会话类型。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ImConversationKind {

    /** 单聊：两个主体之间最多一条会话，由 {@link ImConversation#directKey()} 保证唯一。 */
    SINGLE,

    /** 群聊：成员由 {@link ImMember} 列表定义。 */
    GROUP
}
