package cn.byteforce.seedj.domain;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventDispatch;

/**
 * 聚合根基类。
 *
 * 开发者只需要：
 * <ol>
 *   <li>继承本类，并 {@code implements EventApplier<事件族>}；</li>
 *   <li>为每个事件写一个 {@code protected void apply(X e)} 方法；</li>
 *   <li>在命令方法里调用 {@link #raise(DomainEvent)}；</li>
 *   <li>在工厂方法里调用 {@link #replay(DomainEvent)} 重建历史（通常由框架调用）。</li>
 * </ol>
 *
 * <p>分派按 {@code apply(X)} 的方法签名进行，扫的是 {@code getDeclaredMethods()}
 * 并且允许非 public：{@code apply} 只该由框架和聚合自己调用，{@code protected}
 * 可以避免外部直接改状态。不要尝试 {@code implements EventApplier<A>, EventApplier<B>}：
 * Java 不允许同一泛型接口以不同参数重复实现，编译期即失败。
 *
 * <p>分派、缓存、异常解包全部由本类吸收，开发者不感知反射。
 */
public abstract class AbstractAggregateRoot<ID> extends AbstractEntity<ID> {

    /**
     * 类级分派缓存由 {@link EventDispatch} 统一持有：运行时分派与启动期校验共用同一张表。
     */

    /** 诊断开关：-Ddomain.events.dispatch.debug=true 时打印分派耗时 */
    private static final boolean DEBUG =
            Boolean.getBoolean("domain.events.dispatch.debug");

    private long version;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    /**
     * 新建聚合。版本从 0 开始。
     */
    protected AbstractAggregateRoot(ID id) {
        super(id);
        this.version = 0L;
    }

    /**
     * 从持久化状态重建聚合。版本为已应用事件数。
     * 仅供持久化层/仓储在 rehydrate 时使用，业务代码不应调用。
     */
    protected AbstractAggregateRoot(ID id, long version) {
        super(id);
        if (version < 0) {
            throw new IllegalArgumentException("aggregate version must be >= 0, got: " + version);
        }
        this.version = version;
    }

    // ============================================================
    // 对外唯一的事件入口：raise / replay
    // ============================================================

    /**
     * 命令方法专用：应用事件并注册，两者绑定，结构上不可能遗漏。
     *
     * <p>调用后：
     * <ul>
     *   <li>对应 {@code apply(X)} 被立即执行，聚合状态改变；</li>
     *   <li>事件被加入待提交列表，版本号 +1；</li>
     *   <li>持久化层在事务中通过 {@link #peekDomainEvents()} 取走。</li>
     * </ul>
     */
    protected final void raise(DomainEvent event) {
        dispatch(event);
        domainEvents.add(event);
        this.version++;
    }

    /**
     * 重放专用：只应用状态，不注册事件。
     * 供 rehydrate 使用，命令方法不应调用。
     */
    public final void replay(DomainEvent event) {
        dispatch(event);
    }

    // ============================================================
    // 分派机制（库内部，开发者无需关心）
    // ============================================================

    private void dispatch(DomainEvent event) {
        Objects.requireNonNull(event, "domain event must not be null");

        Method handler = EventDispatch.methodFor(getClass(), event.getClass())
                .orElseThrow(() -> new IllegalStateException(buildMissingHandlerMessage(event)));

        long start = DEBUG ? System.nanoTime() : 0L;
        try {
            handler.invoke(this, event);
        } catch (InvocationTargetException e) {
            // 解包：把业务方法抛出的原始异常原样抛出，
            // 开发者看到的不是无意义的反射包装
            throw sneakyThrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot invoke " + handler + " on " + getClass().getName(), e);
        } finally {
            if (DEBUG) {
                long elapsed = System.nanoTime() - start;
                System.err.printf("[domain-events] %s.%s -> %s took %d ns%n",
                        getClass().getSimpleName(),
                        event.getClass().getSimpleName(),
                        handler.getName(),
                        elapsed);
            }
        }
    }

    private String buildMissingHandlerMessage(DomainEvent event) {
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
     * 把任意 Throwable 原样抛出，绕过 checked 声明。用于解包 InvocationTargetException。
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    // ============================================================
    // 版本与事件收集
    // ============================================================

    /**
     * 当前逻辑版本（已应用事件总数，含尚未持久化的）。
     */
    public long version() {
        return version;
    }

    /**
     * 持久化乐观锁基准版本。
     *
     * <p>公式：version - 未提交事件数。
     *
     * <p>例：从存储加载时 version=5，之后注册了 2 个事件，
     * 当前 version=7，expectedVersion=5。
     * 持久化层必须用 {@code WHERE version = 5} 做 CAS，
     * 而不是 {@code WHERE version = 7}。
     */
    public long expectedVersion() {
        return version - domainEvents.size();
    }

    /**
     * 查看待提交事件但不移除。仓储在 append 成功后再调用
     * {@link #clearDomainEvents()}，避免冲突重试时事件已丢失。
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

    public boolean hasUncommittedEvents() {
        return !domainEvents.isEmpty();
    }

    /**
     * 从持久化历史重建聚合：重放事件并把版本校正到流头版本。
     *
     * <p>由 {@link cn.byteforce.seedj.cqrs.EventSourcedRepository} 在 {@code load} 时调用；
     * 业务代码不应直接调用（命令方法用 {@link #raise}，历史回放用本方法）。
     *
     * <p>{@code targetVersion} 必须不小于当前版本：快照装载时它是流头版本，
     * 全新实例重放时它等于历史事件数。
     */
    public final void replayAll(List<? extends DomainEvent> history, long targetVersion) {
        if (targetVersion < version) {
            throw new IllegalArgumentException(
                    "targetVersion " + targetVersion + " is behind snapshot version " + version);
        }
        for (DomainEvent event : history) {
            dispatch(event);
        }
        this.version = targetVersion;
    }

    // ============================================================
    // 可选：测试期穷尽校验
    // ============================================================

    /**
     * 校验 sealed 事件类型的所有子类型都已有对应的 {@code apply(X)} 方法。
     *
     * <p>启动期已经会对 {@code implements EventApplier<X>} 的聚合自动做这件事，
     * 这里保留给不启容器的纯单元测试（想在没有 Spring 的测试里也守住穷尽性）。
     *
     * <pre>{@code
     * @Test
     * void all_order_events_handled() {
     *     new Order(...).assertAllEventsHandled(OrderEvent.class);
     * }
     * }</pre>
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
}
