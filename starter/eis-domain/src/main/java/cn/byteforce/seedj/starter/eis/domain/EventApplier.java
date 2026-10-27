package cn.byteforce.seedj.starter.eis.domain;

/**
 * 声明「这个聚合处理哪一族事件」。
 *
 * <p>本接口是纯标记：没有方法，只用一个泛型参数携带事件族，让框架在启动期读到它，
 * 检查该族的每个事件都有对应的 {@code apply(X)}。漏写一个处理方法的后果是运行期
 * 分派未命中——所以必须在启动期挡住。</p>
 *
 * <p>{@code apply} 不放进接口方法，是因为接口方法必须 {@code public}，
 * 而 {@code apply} 只该由框架与聚合自己调用；{@code protected} 才能让
 * 「从外部直接改状态」写不出来。分派按方法签名（{@code getDeclaredMethods}）进行。</p>
 *
 * <p>不要写 {@code implements EventApplier<A>, EventApplier<B>}：Java 不允许同一泛型接口
 * 以不同类型参数重复实现，编译期即失败。多个事件族请用 sealed 父类型 + 穷尽 switch，
 * 或同一族内的多个 {@code apply(X)} 重载。</p>
 *
 * @param <E> 事件族（sealed 父类型或单个事件类型）
 * @author ByteForce
 * @since 1.0.0
 */
public interface EventApplier<E extends DomainEvent> {
}
