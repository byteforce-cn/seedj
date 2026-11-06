package cn.byteforce.seedj.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 双时间轴对象存储 SPI。
 *
 * <p>实现方（内存 / PostgreSQL）必须共享 {@link Sealing} 的封口语义，不得自行计算
 * 版本号或系统时间。{@link #append(AppendRequest)} 必须在调用方事务内执行：
 * 对象行、命令记录、审计与事件要一起提交或一起回滚。</p>
 *
 * <p>{@link #find} 的 {@code minVersion} 语义：返回版本低于要求时抛
 * {@code ContractException(EIS-409-003)}，不得返回旧版本冒充当前状态。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface TemporalStore {

    /**
     * 按时点与行范围查询单个对象。
     *
     * <p>行范围在存储层应用；命中范围内无行时返回空，不退化为“全部可见”。</p>
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param query      时点与版本约束
     * @param scope      行范围谓词（不可为空）
     * @return 命中行；无命中或不在范围内返回 {@link Optional#empty()}
     */
    Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                               ScopePredicate scope);

    /**
     * 查询当前版本（写者视角，不应用读范围）。
     *
     * <p>仅供写路径与 handler 使用；读路径必须走 {@code ReadGateway}。</p>
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @return 命中行；无命中返回 {@link Optional#empty()}
     */
    default Optional<TemporalRow> current(String objectType, UUID id) {
        return find(objectType, id, TemporalQuery.current(), ScopePredicate.all());
    }

    /**
     * 封口当前行并追加新行。
     *
     * @param request 追加请求
     * @return 新行与被封口的旧行
     */
    AppendResult append(AppendRequest request);

    /**
     * 列表查询：等值过滤、时点、排序与分页。
     *
     * <p>未知过滤/排序字段、未知对象类型、非法过滤值都抛无效载荷，不得落到 SQL 注入
     * 或 500。{@code total} 是过滤后的总数，不受分页影响。</p>
     *
     * @param search 查询条件
     * @param scope  行范围谓词（不可为空）
     * @return 一页结果
     */
    Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope);

    /**
     * 查询某对象的全部版本，按 {@code recordedAt} 升序。
     *
     * @param objectType 对象类型
     * @param id         对象 ID
     * @param scope      行范围谓词（不可为空）
     * @return 全部版本
     */
    List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope);
}
