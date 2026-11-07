package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventTypeRegistry;
import cn.byteforce.seedj.eventbus.EventEnvelope;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import java.util.Objects;

/**
 * 读模型投影的基类：把"订阅元数据 + 信封解包"收敛到一处，子类只写业务逻辑。
 *
 * <p>订阅的 topic 由事件类的 {@code @EventType} 推导，不需要手写字符串—— 手写 topic 是拼写错误最常见的入口。消费者组取自 {@link
 * #projectionName()}， 一个投影一个组，重放某个投影不影响其他投影的进度。
 *
 * <pre>{@code
 * @Component
 * final class EmployeeDirectoryProjection extends AbstractProjection<EmployeeOnboarded> {
 *
 *     EmployeeDirectoryProjection(EmployeeDirectory directory) {
 *         super(EmployeeOnboarded.class);
 *         this.directory = directory;
 *     }
 *
 *     protected String projectionName() { return "hr.employee.directory"; }
 *
 *     protected void apply(EmployeeOnboarded event) { directory.upsert(event); }
 * }
 * }</pre>
 *
 * <p><b>投递语义是 at-least-once</b>：{@link #apply} 必须幂等（按事件里的聚合标识整体 覆盖，或按 {@code eventId}
 * 去重）。基类不替你记进度：进度由 {@code EventConsumerStore} 按消费者组维护。
 *
 * @param <E> 订阅的事件类型，必须是具体类
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractProjection<E extends DomainEvent> implements EventSubscriber<E> {

    private final Class<E> eventType;

    private final String topic;

    protected AbstractProjection(Class<E> eventType) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.topic = EventTypeRegistry.logicalName(eventType);
    }

    /** 投影名，同时作为消费者组名。改名等于换组，会让该投影从头追一遍历史。 */
    protected abstract String projectionName();

    /** 处理一条事件。必须幂等。 */
    protected abstract void apply(E event);

    /** 订阅的事件类型。 */
    public final Class<E> eventType() {
        return eventType;
    }

    @Override
    public final EventSubscription subscription() {
        String name = projectionName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    getClass().getName() + ": projectionName() must not be blank");
        }
        return EventSubscription.of(name, topic);
    }

    @Override
    public final void onEvent(EventEnvelope<E> envelope) {
        apply(envelope.event());
    }
}
