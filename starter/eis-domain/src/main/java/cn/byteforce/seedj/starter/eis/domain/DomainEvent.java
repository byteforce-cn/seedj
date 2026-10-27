package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;

/**
 * 领域事件：已经发生的事实。
 *
 * <p>事件必须是不可变值对象：字段 {@code final}、只读 getter、无业务行为。
 * 持久化格式由 {@link #eventType()} 与 {@link #schemaVersion()} 决定，
 * 二者都取自 {@link EventType} 注解，业务代码通常不需要覆写。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface DomainEvent {

    /**
     * 返回稳定逻辑名，用于存储、路由与消费位点。
     *
     * @return 逻辑名（{@code @EventType} 的 value；未标注时回退类简单名）
     */
    default String eventType() {
        return EventTypeRegistry.logicalName(getClass());
    }

    /**
     * 返回 payload 的 schema 版本。
     *
     * @return 版本号，最小为 1
     */
    default int schemaVersion() {
        return EventTypeRegistry.schemaVersion(getClass());
    }
}
