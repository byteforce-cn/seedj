package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;

import java.util.List;
import java.util.function.Predicate;

/**
 * 客户端负载均衡（C6）：实例来源 = {@code CoordDiscoveryClient}（registry 线性一致发现 +
 * 缓存降级）；**不含健康标记**——不做主动探活，剔除只基于租约活性 + 消费端失败反馈。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface CoordLoadBalancer {

    /**
     * 查询服务实例（缓存语义由 {@code CoordDiscoveryClient} 决定）。
     *
     * @param serviceName 服务名
     * @return 实例列表（可能为空）
     */
    List<CoordServiceInstance> instances(String serviceName);

    /**
     * 选择一个实例。
     *
     * @param serviceName 服务名
     * @return 选中的实例
     * @throws CoordNoAvailableInstanceException 无可用实例
     */
    CoordServiceInstance select(String serviceName);

    /**
     * 在过滤后的候选集中选择一个实例。
     *
     * @param serviceName 服务名
     * @param filter      候选过滤（如「metadata 含 host/port」）
     * @return 选中的实例
     * @throws CoordNoAvailableInstanceException 过滤后无可用实例
     */
    CoordServiceInstance select(String serviceName, Predicate<CoordServiceInstance> filter);
}
