package cn.byteforce.seedj.domain.event;

/**
 * 声明「这个聚合处理哪一族事件」。
 *
 * <p>聚合<b>必须</b>实现本接口，启动期会检查。本接口是纯标记：没有方法， 只用一个泛型参数携带事件族，让框架在启动期读到它并做穷尽性校验。
 *
 * <p><b>1. sealed 事件族（推荐）</b>
 *
 * <pre>{@code
 * public final class Order extends AbstractAggregateRoot<OrderId>
 *         implements EventApplier<OrderEvent> {
 *
 *     protected void apply(OrderEvent event) {
 *         switch (event) {
 *             case OrderCreated e -> ...
 *             case OrderConfirmed e -> ...
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p><b>2. 单个事件类型</b>
 *
 * <pre>{@code
 * public final class Counter extends AbstractAggregateRoot<CounterId>
 *         implements EventApplier<Incremented> {
 *
 *     protected void apply(Incremented event) { ... }
 * }
 * }</pre>
 *
 * <p>启动期做的事：从泛型参数拿到事件族；族是 sealed 就复用 {@link EventDispatch#missingHandlers} 检查每个子类型都有 handler；
 * 族是单个事件就检查该事件有 handler。新增事件子类型后忘记写 {@code apply}， 应用起不来，而不是等到某次请求才失败。
 *
 * <p>为什么是标记接口而不是 {@code apply} 契约：接口方法必须 {@code public}， 而 {@code apply} 只该由框架和聚合自己调用；{@code
 * public} 会把「从外部直接改状态」 变成合法写法。所以分派按方法签名走（{@code getDeclaredMethods} + {@code setAccessible}），{@code
 * apply} 推荐声明为 {@code protected}。
 *
 * <p><b>禁止</b>{@code implements EventApplier<A>, EventApplier<B>}：Java 不允许同一泛型接口
 * 以不同类型参数重复实现，编译期即失败。多个事件请使用 sealed 父类型 + switch， 或同一族内的多个 {@code apply(X)} 重载。
 */
public interface EventApplier<E extends DomainEvent> {}
