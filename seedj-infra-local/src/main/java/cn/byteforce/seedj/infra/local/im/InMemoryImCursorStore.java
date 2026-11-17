package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.im.spi.ImCursorStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 内存游标存储：单实例开发与测试用。
 *
 * <p>只前进不后退由 {@link Math#max} 保证；多端并发上报在内存实现里以方法同步收敛。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryImCursorStore implements ImCursorStore {

    private final Map<String, Long> read = new HashMap<>();
    private final Map<String, Long> sync = new HashMap<>();

    @Override
    public synchronized long readSeq(UUID conversationId, String subjectId) {
        return read.getOrDefault(readKey(conversationId, subjectId), 0L);
    }

    @Override
    public synchronized long advanceRead(UUID conversationId, String subjectId, long seq) {
        String key = readKey(conversationId, subjectId);
        long next = Math.max(read.getOrDefault(key, 0L), seq);
        read.put(key, next);
        return next;
    }

    @Override
    public synchronized long syncSeq(String tenantId, String subjectId, String deviceId) {
        return sync.getOrDefault(syncKey(tenantId, subjectId, deviceId), 0L);
    }

    @Override
    public synchronized long advanceSync(
            String tenantId, String subjectId, String deviceId, long seq) {
        String key = syncKey(tenantId, subjectId, deviceId);
        long next = Math.max(sync.getOrDefault(key, 0L), seq);
        sync.put(key, next);
        return next;
    }

    private static String readKey(UUID conversationId, String subjectId) {
        return conversationId + "\u0000" + subjectId;
    }

    private static String syncKey(String tenantId, String subjectId, String deviceId) {
        return tenantId + "\u0000" + subjectId + "\u0000" + deviceId;
    }
}
