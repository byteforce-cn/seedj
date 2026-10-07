package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;

import java.util.List;
import java.util.function.Predicate;

/**
 * 负载均衡抽象基类：取实例（含缓存降级）→ 过滤 → 空即抛（C6 不静默直连）→ 子类选择。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public abstract class AbstractCoordLoadBalancer implements CoordLoadBalancer {

    private final CoordDiscoveryClient discoveryClient;

    /**
     * @param discoveryClient 发现消费客户端（含 TTL 缓存与陈旧窗口降级）
     */
    protected AbstractCoordLoadBalancer(CoordDiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    @Override
    public List<CoordServiceInstance> instances(String serviceName) {
        return discoveryClient.getInstances(serviceName);
    }

    @Override
    public CoordServiceInstance select(String serviceName) {
        return select(serviceName, instance -> true);
    }

    @Override
    public CoordServiceInstance select(String serviceName, Predicate<CoordServiceInstance> filter) {
        List<CoordServiceInstance> candidates = instances(serviceName).stream().filter(filter).toList();
        if (candidates.isEmpty()) {
            throw new CoordNoAvailableInstanceException(serviceName);
        }
        return doSelect(serviceName, candidates);
    }

    /**
     * 从非空候选集中选择（子类实现具体策略）。
     *
     * @param serviceName 服务名
     * @param candidates  非空候选集
     * @return 选中的实例
     */
    protected abstract CoordServiceInstance doSelect(String serviceName, List<CoordServiceInstance> candidates);
}
