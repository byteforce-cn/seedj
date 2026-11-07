package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.domain.exception.AggregateNotFoundException;
import java.util.Optional;

/**
 * 聚合的仓储。
 *
 * <p>只做两件事：按标识加载聚合、保存聚合。 没有 add、没有 remove、没有 findAll、没有条件查询。
 *
 * <p>业务删除 = 聚合的业务方法（cancel / close / deactivate）产生事件 + save。
 *
 * <p>条件查询 = QueryService 读投影，不走 Repository。
 *
 * <p>用法：开发者只需声明一个空接口：
 *
 * <pre>{@code
 * public interface OrderRepository extends Repository<Order, OrderId> {}
 * }</pre>
 */
public interface Repository<T extends AbstractAggregateRoot<ID>, ID> {

    /** 按标识加载聚合。 不存在时返回 Optional.empty()，不抛异常。 */
    Optional<T> load(ID id);

    /**
     * 按标识加载聚合，不存在时抛出带修复建议的异常。
     *
     * <p>大多数业务场景用这个方法，避免到处 {@code .orElseThrow(...)}。
     */
    default T loadOrThrow(ID id) {
        return load(id).orElseThrow(
                        () -> new AggregateNotFoundException(getClass().getSimpleName(), id));
    }

    /**
     * 保存聚合。
     *
     * <p>新建和更新都调用此方法，无需区分：
     *
     * <ul>
     *   <li>聚合的 {@code expectedVersion() == 0} 表示新建；
     *   <li>{@code expectedVersion() > 0} 表示更新，库内部用版本 CAS 检测并发冲突。
     * </ul>
     *
     * <p>没有未提交事件时，此方法为 no-op。
     */
    void save(T aggregate);
}
