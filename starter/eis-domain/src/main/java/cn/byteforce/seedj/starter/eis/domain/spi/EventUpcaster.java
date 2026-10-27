package cn.byteforce.seedj.starter.eis.domain.spi;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 一步事件 payload 演进：{@code vN → v(N+1)}。
 *
 * <p>upcaster 在结构化 payload（{@code Map}）上工作，因此不需要为每个历史版本
 * 永久保留一个事件类：字段改名、拆分、合并、补默认值都只是 Map 操作。</p>
 *
 * <p><b>一步一个版本</b>：不允许跨版本（{@code v1 → v3}）——跨版本没人能保证语义正确，
 * 注册时即拒绝。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventUpcaster {

    /**
     * 返回事件逻辑名（{@code @EventType} 的值）。
     *
     * @return 逻辑名
     */
    String eventType();

    /**
     * 返回源版本。
     *
     * @return 源版本（≥ 1）
     */
    int fromVersion();

    /**
     * 返回目标版本，必须等于 {@code fromVersion() + 1}。
     *
     * @return 目标版本
     */
    int toVersion();

    /**
     * 就地把 payload 从 {@code fromVersion} 演进到 {@code toVersion}。
     *
     * @param payload 结构化 payload（可变 Map）
     * @return 演进后的 payload，不允许为 {@code null}
     */
    Map<String, Object> upcast(Map<String, Object> payload);

    /**
     * 构造一步 upcaster。
     *
     * @param eventType   逻辑名
     * @param fromVersion 源版本（≥ 1）
     * @param toVersion   目标版本（必须等于 {@code fromVersion + 1}）
     * @param upcast      演进函数
     * @return upcaster
     * @throws IllegalArgumentException 逻辑名为空白或版本不连续
     */
    static EventUpcaster of(String eventType, int fromVersion, int toVersion,
                            UnaryOperator<Map<String, Object>> upcast) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (fromVersion < 1) {
            throw new IllegalArgumentException("fromVersion must be >= 1, got: " + fromVersion);
        }
        if (toVersion != fromVersion + 1) {
            throw new IllegalArgumentException(
                    "upcaster must advance exactly one version: " + fromVersion + " -> " + toVersion);
        }
        Objects.requireNonNull(upcast, "upcast must not be null");
        return new Step(eventType, fromVersion, toVersion, upcast);
    }

    /**
     * 默认实现。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    final class Step implements EventUpcaster {

        private final String eventType;

        private final int fromVersion;

        private final int toVersion;

        private final UnaryOperator<Map<String, Object>> upcast;

        private Step(String eventType, int fromVersion, int toVersion,
                     UnaryOperator<Map<String, Object>> upcast) {
            this.eventType = eventType;
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.upcast = upcast;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public int fromVersion() {
            return fromVersion;
        }

        @Override
        public int toVersion() {
            return toVersion;
        }

        @Override
        public Map<String, Object> upcast(Map<String, Object> payload) {
            return upcast.apply(payload);
        }

        @Override
        public String toString() {
            return "EventUpcaster[" + eventType + " v" + fromVersion + "->v" + toVersion + "]";
        }
    }
}
