package cn.byteforce.seedj.starter.eis.domain.spi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * upcaster 注册表：把「一条事件从旧版本读到当前版本」拼成链。
 *
 * <p>启动期用它检查「{@code @EventType(version = n)} 是否每一步都有 upcaster」，
 * 避免三年后读到老事件才发现缺一步。</p>
 *
 * <p>线程安全；重复步骤与跨版本步骤在注册时立即失败。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EventUpcasterRegistry {

    private final Map<String, Map<Integer, EventUpcaster>> steps = new ConcurrentHashMap<>();

    /**
     * 注册一步 upcaster。
     *
     * @param upcaster upcaster
     * @throws IllegalStateException 同一事件的同一源版本重复注册
     */
    public void register(EventUpcaster upcaster) {
        Map<Integer, EventUpcaster> byVersion =
                steps.computeIfAbsent(upcaster.eventType(), key -> new ConcurrentHashMap<>());
        EventUpcaster previous = byVersion.putIfAbsent(upcaster.fromVersion(), upcaster);
        if (previous != null) {
            throw new IllegalStateException("""
                    Duplicate upcaster for "%s" v%d: %s and %s.
                    Fix: register exactly one upcaster per version step.
                    """.formatted(upcaster.eventType(), upcaster.fromVersion(), previous, upcaster));
        }
    }

    /**
     * 返回从 {@code fromVersion} 到 {@code toVersion} 的按序链。
     *
     * @param eventType   逻辑名
     * @param fromVersion 存储版本（≤ toVersion）
     * @param toVersion   当前代码版本
     * @return upcaster 链（不需要演进时为空）
     * @throws IllegalStateException 缺少某一步
     */
    public List<EventUpcaster> chainFor(String eventType, int fromVersion, int toVersion) {
        List<EventUpcaster> chain = new ArrayList<>(Math.max(toVersion - fromVersion, 0));
        for (int version = fromVersion; version < toVersion; version++) {
            EventUpcaster step = steps.getOrDefault(eventType, Map.of()).get(version);
            if (step == null) {
                throw new IllegalStateException("""
                        Missing upcaster for "%s" v%d -> v%d.
                        Fix: register EventUpcaster.of("%s", %d, %d, payload -> payload),
                             or redeploy code that knows the stored version.
                        """.formatted(eventType, version, version + 1, eventType, version, version + 1));
            }
            chain.add(step);
        }
        return List.copyOf(chain);
    }

    /**
     * 返回从 v1 到当前版本缺的步骤（供启动期校验）。
     *
     * @param eventType      逻辑名
     * @param currentVersion 当前代码版本
     * @return 缺失步骤描述（空表示完整）
     */
    public List<String> missingSteps(String eventType, int currentVersion) {
        List<String> missing = new ArrayList<>();
        for (int version = 1; version < currentVersion; version++) {
            if (!steps.getOrDefault(eventType, Map.of()).containsKey(version)) {
                missing.add("v" + version + " -> v" + (version + 1));
            }
        }
        return List.copyOf(missing);
    }

    /**
     * 返回已注册 upcaster 覆盖的事件逻辑名。
     *
     * @return 逻辑名集合
     */
    public Set<String> eventTypes() {
        return Set.copyOf(steps.keySet());
    }

    /**
     * 返回某个事件的已注册步骤（按源版本升序）。
     *
     * @param eventType 逻辑名
     * @return 源版本到 upcaster 的映射（不可变）
     */
    public Map<Integer, EventUpcaster> stepsOf(String eventType) {
        Map<Integer, EventUpcaster> copy = new LinkedHashMap<>();
        steps.getOrDefault(eventType, Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> copy.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(copy);
    }
}
