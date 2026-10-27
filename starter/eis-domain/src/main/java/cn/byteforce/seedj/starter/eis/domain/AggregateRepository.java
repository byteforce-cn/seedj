package cn.byteforce.seedj.starter.eis.domain;

import java.util.Optional;

/**
 * 聚合仓储：只做「按标识加载」与「保存」。
 *
 * <p>没有 {@code findAll}、没有条件查询、没有 {@code delete}——这是故意的：</p>
 * <ul>
 *   <li>条件查询走读模型（投影 + 查询服务），不走仓储；</li>
 *   <li>业务删除 = 业务方法产生事件 + 保存，历史必须留下。</li>
 * </ul>
 *
 * @param <T>  聚合类型
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface AggregateRepository<T extends AggregateRoot<ID>, ID> {

    /**
     * 按标识加载聚合。
     *
     * @param id 聚合标识
     * @return 聚合；不存在时为空
     */
    Optional<T> load(ID id);

    /**
     * 按标识加载聚合，不存在时抛出带修复建议的异常。
     *
     * <p>大多数业务场景用这个方法，避免调用方到处写 {@code orElseThrow}。</p>
     *
     * @param id 聚合标识
     * @return 聚合（不为 {@code null}）
     * @throws AggregateNotFoundException 聚合不存在
     */
    default T loadOrThrow(ID id) {
        return load(id).orElseThrow(() -> new AggregateNotFoundException(
                getClass().getSimpleName(), id));
    }

    /**
     * 保存聚合。
     *
     * <p>新建与更新调用同一个方法：{@code expectedVersion() == 0} 表示新建，
     * 大于 0 表示更新并以版本 CAS 检测并发冲突。没有未提交事件时为 no-op。</p>
     *
     * @param aggregate 聚合
     * @throws OptimisticLockingException 版本冲突
     */
    void save(T aggregate);
}
