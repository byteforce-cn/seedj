package cn.byteforce.seedj.lock;

import java.util.Objects;

/**
 * 一次加锁的租约。关闭即释放。
 *
 * <p>租约是"用时间换活性"的：持有者崩溃不会永久占锁，{@code ttl} 到期后其他实例
 * 可以重新获取。因此<b>租约过期不等于业务已经结束</b>，临界区内的操作仍必须幂等，
 * 或者用存储侧的唯一约束兜底。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface LockLease extends AutoCloseable {

    /**
     * 被锁定的键。
     */
    String key();

    /**
     * 释放锁。幂等；只能释放自己持有的租约（实现负责校验持有者标识）。
     */
    @Override
    void close();

    /**
     * 简单实现的工厂。
     *
     * @param key     锁键
     * @param release 释放动作，只在第一次 {@link #close()} 时执行
     */
    static LockLease of(String key, Runnable release) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(release, "release");
        return new LockLease() {

            private boolean closed;

            @Override
            public String key() {
                return key;
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    release.run();
                }
            }
        };
    }
}
