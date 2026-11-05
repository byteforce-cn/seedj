package cn.byteforce.seedj.transit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 磁盘状态：多服务指向同一文件即共享同一套密钥与版本历史。
 *
 * <p>每次读取都重新加载文件（本地开发/单机多服务场景，文件很小），
 * 写操作在文件锁内完成，因此不存在"内存视图落后于文件"的问题。
 */
final class FileTransitState implements TransitState {

    private final TransitStateFile file;

    FileTransitState(TransitStateFile file) {
        this.file = file;
    }

    @Override
    public Map<String, StoredKey> snapshot() {
        return file.read();
    }

    @Override
    public <T> T update(Function<Map<String, StoredKey>, T> mutation) {
        return file.update(state -> mutation.apply(state));
    }

    /**
     * 便于诊断：当前已持久化的 key 名称。
     */
    Map<String, StoredKey> debugSnapshot() {
        return new LinkedHashMap<>(file.read());
    }
}
