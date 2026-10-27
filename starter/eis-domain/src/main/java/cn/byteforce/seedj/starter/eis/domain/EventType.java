package cn.byteforce.seedj.starter.eis.domain;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明事件的稳定逻辑名与 payload 的 schema 版本。
 *
 * <p>逻辑名写进 {@code evt_log.event_type}，并用于路由监听器：它与 Java 类名解耦，
 * 类重命名或换包后历史事件依然可读。逻辑名缺失时回退类简单名，只建议测试期如此。</p>
 *
 * <p>版本从 1 开始。抬高版本必须同步注册一条 {@link EventUpcaster}，
 * 否则启动期校验失败——旧事件在三年后读到时才炸是不可接受的。</p>
 *
 * <pre>{@code
 * @EventType("account.opened")
 * public final class AccountOpened implements AccountEvent { ... }
 * }</pre>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventType {

    /**
     * 稳定逻辑名，建议 {@code 聚合.动作} 小写点分格式。
     *
     * @return 逻辑名
     */
    String value();

    /**
     * payload 的 schema 版本，从 1 开始。
     *
     * @return schema 版本
     */
    int version() default 1;
}
