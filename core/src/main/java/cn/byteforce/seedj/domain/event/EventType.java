package cn.byteforce.seedj.domain.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明事件的稳定逻辑名与 schema 版本。
 *
 * <p>逻辑名用于写入 {@code evt_log.event_type}、路由监听器与 MQ topic，
 * 必须与 Java 类名解耦：类重命名/移动包时历史事件仍可回放。
 *
 * <pre>{@code
 * @EventType("account.opened")
 * public record AccountOpened(long accountId, String owner) implements DomainEvent {}
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventType {

    /**
     * 稳定逻辑名，建议使用 {@code 聚合.动作} 小写点分格式。
     */
    String value();

    /**
     * 事件 payload 的 schema 版本，从 1 开始。
     */
    int version() default 1;
}
