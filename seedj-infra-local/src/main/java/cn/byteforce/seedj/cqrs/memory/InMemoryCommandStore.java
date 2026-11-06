package cn.byteforce.seedj.cqrs.memory;

import cn.byteforce.seedj.cqrs.CommandRecord;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.spi.CommandStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存命令存储（单进程，不跨服务共享）。
 *
 * <p>{@code insert}/{@code complete} 在同一个对象锁内完成“检查 + 写入”，
 * 保证幂等键唯一与终态不可覆盖；{@code find} 无锁读取不可变记录。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryCommandStore implements CommandStore {

    private final Map<UUID, CommandRecord> byId = new ConcurrentHashMap<>();

    private final Map<String, UUID> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<CommandRecord> find(UUID id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    @Override
    public Optional<CommandRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        UUID id = byKey.get(idempotencyKey);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized boolean insert(CommandRecord record) {
        if (byId.containsKey(record.id()) || byKey.containsKey(record.idempotencyKey())) {
            return false;
        }
        byId.put(record.id(), record);
        byKey.put(record.idempotencyKey(), record.id());
        return true;
    }

    @Override
    public synchronized void complete(CommandRecord record) {
        CommandRecord existing = byId.get(record.id());
        if (existing == null) {
            throw new IllegalStateException("command record not found: " + record.id());
        }
        if (existing.equals(record)) {
            return;
        }
        if (existing.status() != CommandStatus.PENDING) {
            throw new IllegalStateException("command record already completed: "
                    + record.id() + " (" + existing.status().wireName() + ")");
        }
        if (!record.isTerminal()) {
            throw new IllegalStateException("complete requires a terminal record: " + record.id());
        }
        byId.put(record.id(), record);
    }

    @Override
    public synchronized void abandon(UUID id) {
        CommandRecord existing = id == null ? null : byId.get(id);
        if (existing == null || existing.status() != CommandStatus.PENDING) {
            return;
        }
        byId.remove(id);
        byKey.remove(existing.idempotencyKey(), id);
    }

    /**
     * 清空全部记录（测试用）。
     */
    public void clear() {
        byId.clear();
        byKey.clear();
    }
}
