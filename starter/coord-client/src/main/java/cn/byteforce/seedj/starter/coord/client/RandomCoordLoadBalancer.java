package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡（C6）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class RandomCoordLoadBalancer extends AbstractCoordLoadBalancer {

    /**
     * @param discoveryClient 发现消费客户端
     */
    public RandomCoordLoadBalancer(CoordDiscoveryClient discoveryClient) {
        super(discoveryClient);
    }

    @Override
    protected CoordServiceInstance doSelect(String serviceName, List<CoordServiceInstance> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
