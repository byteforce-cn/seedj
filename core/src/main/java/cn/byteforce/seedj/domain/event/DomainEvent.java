package cn.byteforce.seedj.domain.event;

public interface DomainEvent {

    /**
     * 稳定逻辑名，用于存储、路由与 MQ topic。
     *
     * <p>默认取 {@link EventType} 注解；未标注时回退类简单名（仅建议测试使用）。
     */
    default String eventType() {
        return EventTypeRegistry.logicalName(getClass());
    }

    /**
     * payload 的 schema 版本。
     */
    default int schemaVersion() {
        return EventTypeRegistry.schemaVersion(getClass());
    }
}
