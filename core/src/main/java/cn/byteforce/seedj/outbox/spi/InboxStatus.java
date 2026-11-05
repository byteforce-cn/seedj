package cn.byteforce.seedj.outbox.spi;

/**
 * Inbox 处理状态。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum InboxStatus {
    PROCESSING,
    DONE,
    DEAD
}
