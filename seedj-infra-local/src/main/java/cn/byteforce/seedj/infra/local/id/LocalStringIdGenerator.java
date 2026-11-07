package cn.byteforce.seedj.infra.local.id;

import cn.byteforce.seedj.id.IdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单进程回退生成器：进程号 + 启动时刻 + 原子序号。
 *
 * <p>仅用于不经过 Spring 装配的直接构造（测试、嵌入式内存实现）。生产环境应注入 {@link StringIdGenerator}（雪花 + 节点编号），不要依赖本类的跨进程唯一性。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LocalStringIdGenerator implements IdGenerator<String> {

    private static final String PROCESS_PREFIX =
            ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime());

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String id() {
        return PROCESS_PREFIX + "-" + sequence.incrementAndGet();
    }
}
