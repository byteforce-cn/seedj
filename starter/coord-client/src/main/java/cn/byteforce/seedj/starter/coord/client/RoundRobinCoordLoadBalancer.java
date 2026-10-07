package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡（默认策略；C6）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class RoundRobinCoordLoadBalancer extends AbstractCoordLoadBalancer {

    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * @param discoveryClient 发现消费客户端
     */
    public RoundRobinCoordLoadBalancer(CoordDiscoveryClient discoveryClient) {
        super(discoveryClient);
    }

    @Override
    protected CoordServiceInstance doSelect(String serviceName, List<CoordServiceInstance> candidates) {
        int index = counters.computeIfAbsent(serviceName, key -> new AtomicInteger()).getAndIncrement();
        return candidates.get(Math.floorMod(index, candidates.size()));
    }
}
