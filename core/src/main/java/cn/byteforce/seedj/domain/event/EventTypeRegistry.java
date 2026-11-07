package cn.byteforce.seedj.domain.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件逻辑名 ↔ 事件类的注册表。
 *
 * <p>框架只允许通过稳定逻辑名读写事件类型：
 *
 * <ul>
 *   <li>写入：{@link #nameOf(Class)}，优先取 {@link EventType#value()}；
 *   <li>读取：{@link #resolve(String)}，禁止 {@code Class.forName}。
 * </ul>
 *
 * <p>实现是线程安全的内存表。应用启动时由 {@code domain-spring} 扫描 {@link DomainEvent} 实现并注册；写入未知类型时也会按注解/类名即时登记。
 */
public final class EventTypeRegistry {

    /** 一条注册信息。 */
    public record Registration(String name, int version, Class<? extends DomainEvent> type) {}

    private final Map<String, Registration> byName = new ConcurrentHashMap<>();
    private final Map<Class<?>, Registration> byType = new ConcurrentHashMap<>();

    /** 注册一个事件类型。重复注册同名但不同类会立即失败，避免路由歧义。 */
    public void register(Class<? extends DomainEvent> type) {
        Registration registration = registrationOf(type);
        Registration previous = byName.putIfAbsent(registration.name(), registration);
        if (previous != null && !previous.type().equals(type)) {
            throw new IllegalStateException(
                    """
                    Event type name collision: "%s" is claimed by both %s and %s.
                    Fix: give each event a unique @EventType value.
                    """
                            .formatted(
                                    registration.name(),
                                    previous.type().getName(),
                                    type.getName()));
        }
        byType.put(type, registration);
    }

    /** 返回事件类型的稳定逻辑名；类型未注册时即时登记。 */
    public String nameOf(Class<?> type) {
        return registrationOf(type).name();
    }

    /** 返回事件 payload 的 schema 版本。 */
    public int versionOf(Class<?> type) {
        return registrationOf(type).version();
    }

    /**
     * 按逻辑名解析事件类。
     *
     * @throws IllegalArgumentException 名称未注册，通常意味着事件包未被扫描
     */
    public Class<? extends DomainEvent> resolve(String name) {
        Registration registration = byName.get(name);
        if (registration == null) {
            throw new IllegalArgumentException(
                    """
                    Unknown event type "%s".
                    Registered types: %s
                    Fix: ensure the event class is under a scanned package (domain.events.event-packages),
                         or call EventTypeRegistry.register(...) explicitly.
                    """
                            .formatted(name, byName.keySet()));
        }
        return registration.type();
    }

    public Optional<Registration> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(byName.keySet());
    }

    public Map<String, Class<? extends DomainEvent>> snapshot() {
        Map<String, Class<? extends DomainEvent>> copy = new LinkedHashMap<>();
        byName.forEach((name, registration) -> copy.put(name, registration.type()));
        return Collections.unmodifiableMap(copy);
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
        Registration created = registrationOfType(eventType);
        byType.putIfAbsent(eventType, created);
        byName.putIfAbsent(created.name(), created);
        return created;
    }

    private static Registration registrationOfType(Class<? extends DomainEvent> type) {
        return new Registration(logicalName(type), schemaVersion(type), type);
    }

    /** 稳定逻辑名：优先 {@link EventType#value()}，否则回退类简单名。 */
    public static String logicalName(Class<?> type) {
        EventType annotation = type.getAnnotation(EventType.class);
        if (annotation == null) {
            return type.getSimpleName();
        }
        if (annotation.value().isBlank()) {
            throw new IllegalStateException(
                    "@EventType value must not be blank on " + type.getName());
        }
        return annotation.value();
    }

    /** schema 版本：优先 {@link EventType#version()}，否则为 1。 */
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
}
