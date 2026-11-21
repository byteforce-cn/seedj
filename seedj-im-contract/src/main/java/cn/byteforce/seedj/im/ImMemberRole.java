package cn.byteforce.seedj.im;

/**
 * 会话成员角色。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ImMemberRole {

    /** 群主：唯一，可解散群、可授予管理员。 */
    OWNER,

    /** 管理员：可加人、踢人、改群名。 */
    ADMIN,

    /** 普通成员：可发送与读取。 */
    MEMBER
}
