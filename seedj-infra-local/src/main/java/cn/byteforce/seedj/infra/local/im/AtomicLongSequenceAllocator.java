package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内单调序号：单实例开发与测试用。
 *
 * <p>重启归零、不跨节点，因此只能用于内存存储模式。生产用 PostgreSQL 序列 （{@code JdbcImSequenceAllocator}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class AtomicLongSequenceAllocator implements ImSequenceAllocator {

    private final AtomicLong next = new AtomicLong();

    @Override
    public long next() {
        return next.incrementAndGet();
    }
}
