package cn.byteforce.seedj.core.clock.spi;

import cn.byteforce.seedj.core.clock.HybridTimestamp;

/**
 * HLC 状态存取 SPI，由业务按所用中间件实现。
 */
public interface HybridStateStore {

    /** 读取本节点持久化状态，不存在返回 {@code null}。 */
    HybridTimestamp load(String nodeId);

    /** 持久化本节点状态，必须保证不倒退。 */
    void save(String nodeId, HybridTimestamp state);
}
