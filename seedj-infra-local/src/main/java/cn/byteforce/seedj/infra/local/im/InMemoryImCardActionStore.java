package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.im.ImCardAction;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存卡片动作存储：单实例开发/测试用，语义与 JDBC 实现一致。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class InMemoryImCardActionStore implements ImCardActionStore {

    private final ConcurrentHashMap<String, Entry> rows = new ConcurrentHashMap<>();

    @Override
    public Optional<Map<String, Object>> begin(ImCardAction action) {
        String key = key(action);
        Entry existing = rows.putIfAbsent(key, new Entry(false, Map.of()));
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.done) {
            return Optional.of(existing.result);
        }
        throw new ContractException(ContractErrorCode.VERSION_CONFLICT, "card action in progress");
    }

    @Override
    public void complete(ImCardAction action, Map<String, Object> result) {
        String key = key(action);
        rows.compute(
                key,
                (ignored, existing) -> {
                    if (existing == null || existing.done) {
                        return existing;
                    }
                    return new Entry(true, new LinkedHashMap<>(result));
                });
    }

    @Override
    public void abandon(ImCardAction action) {
        String key = key(action);
        rows.computeIfPresent(key, (ignored, existing) -> existing.done ? existing : null);
    }

    private static String key(ImCardAction action) {
        return action.messageId() + "|" + action.actionId();
    }

    private record Entry(boolean done, Map<String, Object> result) {}
}
