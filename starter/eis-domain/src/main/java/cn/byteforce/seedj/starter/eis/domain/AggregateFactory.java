package cn.byteforce.seedj.starter.eis.domain;

import java.util.List;

/**
 * 从事件历史重建聚合。
 *
 * <p>库内部用它把「事件流」变成「聚合实例」，业务代码不直接调用——
 * 通常直接传聚合的静态 {@code rehydrate} 方法引用：</p>
 *
 * <pre>{@code
 * AggregateFactory<Account, AccountId> factory = Account::rehydrate;
 * }</pre>
 *
 * @param <T>  聚合类型
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface AggregateFactory<T extends AggregateRoot<ID>, ID> {

    /**
     * 重建聚合。
     *
     * @param id      聚合标识
     * @param version 事件历史总数（等于重建后的 {@code version()}）
     * @param history 按版本升序的历史事件
     * @return 重建后的聚合
     */
    T rehydrate(ID id, long version, List<DomainEvent> history);
}
