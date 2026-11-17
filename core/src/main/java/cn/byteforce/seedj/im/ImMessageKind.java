package cn.byteforce.seedj.im;

/**
 * 消息内容类型。
 *
 * <p>只描述 body 的解释方式，不描述渲染：{@code IMAGE} / {@code FILE} 的 body 里放引用（对象存储 key）， 不放二进制。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum ImMessageKind {

    /** 纯文本。 */
    TEXT,

    /** 图片引用。 */
    IMAGE,

    /** 文件引用。 */
    FILE,

    /** 系统消息：入群、退群、改群名等，发送者是系统。 */
    SYSTEM
}
