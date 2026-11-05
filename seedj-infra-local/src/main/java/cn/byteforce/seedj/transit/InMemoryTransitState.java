package cn.byteforce.seedj.transit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 纯内存状态：进程重启即丢失，仅用于单元测试与显式声明的临时场景。
 */
final class InMemoryTransitState implements TransitState {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, StoredKey> keys = new LinkedHashMap<>();

    @Override
    public Map<String, StoredKey> snapshot() {
        lock.lock();
        try {
            return Map.copyOf(keys);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> T update(Function<Map<String, StoredKey>, T> mutation) {
        lock.lock();
        try {
            return mutation.apply(keys);
        } finally {
            lock.unlock();
        }
    }
}
