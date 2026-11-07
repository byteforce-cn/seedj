package cn.byteforce.seedj.infra.local.security;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存安全审计通道：开发默认实现与测试断言用。
 *
 * <p>容量有上限，避免长时间运行把堆撑爆；超出后丢弃最旧事件（与 {@code IamAuditSink} 的历史行为一致）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class InMemorySecurityAuditSink implements SecurityAuditSink {

    private final int capacity;
    private final ConcurrentLinkedDeque<SecurityAuditEvent> events = new ConcurrentLinkedDeque<>();

    public InMemorySecurityAuditSink() {
        this(2000);
    }

    public InMemorySecurityAuditSink(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void record(SecurityAuditEvent event) {
        events.addLast(event);
        while (events.size() > capacity) {
            events.pollFirst();
        }
    }

    /** 已记录事件快照（按写入顺序）。 */
    public List<SecurityAuditEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
