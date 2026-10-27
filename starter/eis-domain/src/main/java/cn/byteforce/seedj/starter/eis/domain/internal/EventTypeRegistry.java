package cn.byteforce.seedj.starter.eis.domain.internal;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件逻辑名与事件类的注册表（库内部，不属于业务 API）。
 *
 * <p>只允许通过稳定逻辑名读写事件类型：写入取 {@link EventType#value()}，
 * 读取按逻辑名解析，禁止 {@code Class.forName}。逻辑名与 Java 类名解耦，
 * 类重命名后历史事件仍可回读。</p>
 *
 * <p>线程安全；同名不同类的注册立即失败，避免路由歧义。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class EventTypeRegistry {

    private final Map<String, Registration> byName = new ConcurrentHashMap<>();

    private final Map<Class<?>, Registration> byType = new ConcurrentHashMap<>();

    /**
     * 注册事件类型；同名但不同类时立即失败。
     *
     * @param type 事件类型
     * @throws IllegalStateException 逻辑名冲突
     */
    public void register(Class<? extends DomainEvent> type) {
        Registration registration = registrationOf(type);
        Registration previous = byName.putIfAbsent(registration.name(), registration);
        if (previous != null && !previous.type().equals(type)) {
            throw new IllegalStateException("""
                    Event type name collision: "%s" is claimed by both %s and %s.
                    Fix: give each event a unique @EventType value.
                    """.formatted(registration.name(), previous.type().getName(), type.getName()));
        }
        byType.put(type, registration);
    }

    /**
     * 返回事件类型的逻辑名；未注册时即时登记。
     *
     * @param type 事件类型
     * @return 逻辑名
     */
    public String nameOf(Class<?> type) {
        return registrationOf(type).name();
    }

    /**
     * 返回事件 payload 的 schema 版本。
     *
     * @param type 事件类型
     * @return 版本号
     */
    public int versionOf(Class<?> type) {
        return registrationOf(type).version();
    }

    /**
     * 按逻辑名解析事件类。
     *
     * @param name 逻辑名
     * @return 事件类型
     * @throws IllegalArgumentException 名称未注册（通常是事件包未被扫描）
     */
    public Class<? extends DomainEvent> resolve(String name) {
        Registration registration = byName.get(name);
        if (registration == null) {
            throw new IllegalArgumentException("""
                    Unknown event type "%s".
                    Registered types: %s
                    Fix: ensure the event class is registered (scan its package or call
                         EventTypeRegistry.register(...) explicitly).
                    """.formatted(name, byName.keySet()));
        }
        return registration.type();
    }

    /**
     * 按逻辑名查找注册信息。
     *
     * @param name 逻辑名
     * @return 注册信息；不存在时为空
     */
    public Optional<Registration> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * 返回已注册的逻辑名集合。
     *
     * @return 逻辑名集合
     */
    public Set<String> names() {
        return Set.copyOf(byName.keySet());
    }

    /**
     * 返回逻辑名到事件类的不可变快照。
     *
     * @return 快照
     */
    public Map<String, Class<? extends DomainEvent>> snapshot() {
        Map<String, Class<? extends DomainEvent>> copy = new LinkedHashMap<>();
        byName.forEach((name, registration) -> copy.put(name, registration.type()));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 计算稳定逻辑名：优先注解，否则回退类简单名。
     *
     * @param type 事件类型
     * @return 逻辑名
     * @throws IllegalStateException 注解值为空白
     */
    public static String logicalName(Class<?> type) {
        EventType annotation = type.getAnnotation(EventType.class);
        if (annotation == null) {
            return type.getSimpleName();
        }
        if (annotation.value().isBlank()) {
            throw new IllegalStateException("@EventType value must not be blank on " + type.getName());
        }
        return annotation.value();
    }

    /**
     * 计算 schema 版本：优先注解，否则为 1。
     *
     * @param type 事件类型
     * @return 版本号
     * @throws IllegalStateException 注解版本小于 1
     */
    public static int schemaVersion(Class<?> type) {
        EventType annotation = type.getAnnotation(EventType.class);
        if (annotation == null) {
            return 1;
        }
        if (annotation.version() < 1) {
            throw new IllegalStateException("@EventType version must be >= 1 on " + type.getName());
        }
        return annotation.version();
    }

    private Registration registrationOf(Class<?> type) {
        Registration registration = byType.get(type);
        if (registration != null) {
            return registration;
        }
        if (!DomainEvent.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " does not implement DomainEvent");
        }
        @SuppressWarnings("unchecked")
        Class<? extends DomainEvent> eventType = (Class<? extends DomainEvent>) type;
        Registration created = new Registration(logicalName(eventType), schemaVersion(eventType), eventType);
        byType.putIfAbsent(eventType, created);
        byName.putIfAbsent(created.name(), created);
        return created;
    }

    /**
     * 一条注册信息：逻辑名、schema 版本、事件类型。
     *
     * @author ByteForce
     * @since 1.0.0
     */
    public static final class Registration {

        private final String name;

        private final int version;

        private final Class<? extends DomainEvent> type;

        /**
         * 构造注册信息。
         *
         * @param name    逻辑名
         * @param version schema 版本
         * @param type    事件类型
         */
        public Registration(String name, int version, Class<? extends DomainEvent> type) {
            this.name = name;
            this.version = version;
            this.type = type;
        }

        /**
         * 返回逻辑名。
         *
         * @return 逻辑名
         */
        public String name() {
            return name;
        }

        /**
         * 返回 schema 版本。
         *
         * @return 版本号
         */
        public int version() {
            return version;
        }

        /**
         * 返回事件类型。
         *
         * @return 事件类型
         */
        public Class<? extends DomainEvent> type() {
            return type;
        }
    }
}
