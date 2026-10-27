package cn.byteforce.seedj.starter.eis.domain;

/**
 * 带标识的对象。
 *
 * <p>聚合与实体的标识由业务定义（{@code AccountId}、{@code OrderId} 等值对象），
 * 框架只用它做事件流定位，不对其类型做假设。</p>
 *
 * @param <ID> 标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface Identified<ID> {

    /**
     * 返回标识。
     *
     * @return 标识
     */
    ID getId();
}
