package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.starter.eis.domain.internal.EventDispatch;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 聚合根基类：命令改状态、事件记录事实，两者绑定为一次原子操作。
 *
 * <p>业务侧只需要四件事：</p>
 * <ol>
 *   <li>继承本类，并 {@code implements EventApplier<事件族>}；</li>
 *   <li>为每个事件写一个 {@code protected void apply(X e)}（推荐 {@code protected}）；</li>
 *   <li>命令方法里调用 {@link #raise(DomainEvent)}；</li>
 *   <li>提供 {@code static rehydrate(ID, long, List<DomainEvent>)} 供仓储重建。</li>
 * </ol>
 *
 * <p>本类吸收反射分派与异常解包：业务方法抛出的原始异常会原样抛出，
 * 开发者看到的不是无意义的 {@link InvocationTargetException} 包装。</p>
 *
 * @param <ID> 聚合适配标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AggregateRoot<ID> extends AbstractEntity<ID> {

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private long version;

    /**
     * 构造新建聚合，版本从 0 开始。
     *
     * @param id 聚合标识
     */
    protected AggregateRoot(ID id) {
        super(id);
        this.version = 0L;
    }

    /**
     * 从持久化状态重建聚合，版本为已应用事件总数。
     *
     * <p>仅供持久化层与 {@code rehydrate} 使用，业务代码不应调用。</p>
     *
     * @param id      聚合标识
     * @param version 已应用事件总数，不允许为负数
     * @throws IllegalArgumentException 版本为负数
     */
    protected AggregateRoot(ID id, long version) {
        super(id);
        if (version < 0) {
            throw new IllegalArgumentException("aggregate version must be >= 0, got: " + version);
        }
        this.version = version;
    }

    /**
     * 命令方法专用：应用事件并登记，两者结构上不可分离。
     *
     * <p>调用后：对应的 {@code apply(X)} 立即执行、事件进入待提交列表、版本 +1。
     * 持久化层在事务内通过 {@link #peekDomainEvents()} 取走事件，append 成功后
     * 再调用 {@link #clearDomainEvents()}。</p>
     *
     * @param event 领域事件
     * @throws NullPointerException  事件为 {@code null}
     * @throws IllegalStateException 聚合缺少对应的 {@code apply(X)}
     */
    protected final void raise(DomainEvent event) {
        dispatch(event);
        domainEvents.add(event);
        this.version++;
    }

    /**
     * 重放专用：只应用状态，不登记事件、不改变已提交版本语义。
     *
     * <p>供 {@code rehydrate} 重建历史使用；命令方法不应调用。</p>
     *
     * @param event 历史事件
     * @throws NullPointerException  事件为 {@code null}
     * @throws IllegalStateException 聚合缺少对应的 {@code apply(X)}
     */
    public final void replay(DomainEvent event) {
        dispatch(event);
    }

    /**
     * 返回当前逻辑版本（已应用事件总数，含尚未持久化的）。
     *
     * @return 版本号
     */
    public long version() {
        return version;
    }

    /**
     * 返回持久化乐观锁的基准版本。
     *
     * <p>公式：{@code version - 未提交事件数}。例：从存储加载时 version=5，
     * 之后登记 2 个事件，当前 version=7、expectedVersion=5；持久化必须以
     * {@code WHERE version = 5} 做 CAS，而不是 {@code WHERE version = 7}。</p>
     *
     * @return 期望版本
     */
    public long expectedVersion() {
        return version - domainEvents.size();
    }

    /**
     * 查看待提交事件（不可变快照），不移除。
     *
     * <p>事件只能在 append 成功之后清空，否则版本冲突重试时事件已丢失。</p>
     *
     * @return 待提交事件（按发生顺序）
     */
    public List<DomainEvent> peekDomainEvents() {
        return List.copyOf(domainEvents);
    }

    /**
     * 清空待提交事件。只能在 append 成功之后调用。
     */
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    /**
     * 是否存在尚未持久化的事件。
     *
     * @return 存在未提交事件时为 {@code true}
     */
    public boolean hasUncommittedEvents() {
        return !domainEvents.isEmpty();
    }

    /**
     * 校验 sealed 事件类型的所有子类型都有对应的 {@code apply(X)}。
     *
     * <p>启动期校验已对声明了 {@link EventApplier} 的聚合自动执行；本方法留给
     * 不启容器、也不依赖启动校验的纯单元测试。</p>
     *
     * @param sealedEventType sealed 事件父类型
     * @throws IllegalArgumentException 传入类型不是 sealed
     * @throws AssertionError           存在未覆盖的子类型
     */
    public final void assertAllEventsHandled(Class<?> sealedEventType) {
        List<Class<?>> missing = EventDispatch.missingHandlers(getClass(), sealedEventType);
        if (missing.isEmpty()) {
            return;
        }
        List<String> names = missing.stream().map(Class::getSimpleName).toList();
        throw new AssertionError("""
                %s does not handle all events of sealed type %s.
                Missing: %s
                Fix: add protected void apply(...) for each missing event,
                     or a single protected void apply(%s event) with an exhaustive switch.
                """.formatted(
                getClass().getSimpleName(),
                sealedEventType.getSimpleName(),
                names,
                sealedEventType.getSimpleName()));
    }

    private void dispatch(DomainEvent event) {
        Objects.requireNonNull(event, "domain event must not be null");

        Method handler = EventDispatch.methodFor(getClass(), event.getClass())
                .orElseThrow(() -> new IllegalStateException(missingHandlerMessage(event)));
        try {
            handler.invoke(this, event);
        } catch (InvocationTargetException e) {
            throw sneakyThrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot invoke " + handler + " on " + getClass().getName(), e);
        }
    }

    private String missingHandlerMessage(DomainEvent event) {
        return """
                %s has no apply(%s) method, but received an event of that type.
                Fix: add to %s:
                    protected void apply(%s e) { ... }
                and declare the event family:
                    implements EventApplier<%s>
                """.formatted(
                getClass().getSimpleName(),
                event.getClass().getSimpleName(),
                getClass().getSimpleName(),
                event.getClass().getSimpleName(),
                event.getClass().getSimpleName());
    }

    /**
     * 把任意 {@link Throwable} 原样抛出，绕过 checked 声明：用于解包反射包装。
     *
     * @param throwable 原始异常
     * @param <T>       声明返回类型（实际永不返回）
     * @return 永不返回
     * @throws T 原始异常
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
