package cn.byteforce.seedj.starter.coord.client;

import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import cn.byteforce.seedj.starter.coord.registry.CoordDiscoveryClient;
import cn.byteforce.seedj.starter.coord.registry.CoordRegistryProperties;
import cn.byteforce.seedj.starter.coord.registry.CoordServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 负载均衡策略测试（C6）：轮询顺序、随机成员、空集合 fail loud、过滤。
 *
 * <p>SDK {@code ServiceInstance} 构造器序：{@code (instanceId, serviceName, metadata)}。</p>
 */
class CoordLoadBalancerTest {

    private final Registry registry = mock(Registry.class);

    private CoordDiscoveryClient discoveryClient() {
        CoordRegistryProperties.Discovery discovery = new CoordRegistryProperties.Discovery();
        discovery.setCacheEnabled(false);
        return new CoordDiscoveryClient(registry, discovery);
    }

    private void stubInstances(String serviceName, String... addresses) {
        List<ServiceInstance> instances = new java.util.ArrayList<>();
        for (int i = 0; i < addresses.length; i++) {
            instances.add(new ServiceInstance("i-" + i, serviceName,
                    "{\"address\":\"" + addresses[i] + "\"}"));
        }
        when(registry.discover(serviceName)).thenReturn(instances);
    }

    @Test
    void roundRobinAlternatesInOrder() {
        stubInstances("orders", "10.0.0.1:8080", "10.0.0.2:8080");
        CoordLoadBalancer lb = new RoundRobinCoordLoadBalancer(discoveryClient());

        assertThat(lb.select("orders").getInstanceId()).isEqualTo("i-0");
        assertThat(lb.select("orders").getInstanceId()).isEqualTo("i-1");
        assertThat(lb.select("orders").getInstanceId()).isEqualTo("i-0");
    }

    @Test
    void randomSelectsFromCandidates() {
        stubInstances("orders", "10.0.0.1:8080", "10.0.0.2:8080");
        CoordLoadBalancer lb = new RandomCoordLoadBalancer(discoveryClient());

        assertThat(lb.select("orders").getInstanceId()).isIn("i-0", "i-1");
    }

    @Test
    void throwsWhenNoInstances() {
        stubInstances("orders");
        CoordLoadBalancer lb = new RoundRobinCoordLoadBalancer(discoveryClient());

        assertThatThrownBy(() -> lb.select("orders"))
                .isInstanceOf(CoordNoAvailableInstanceException.class)
                .hasMessageContaining("orders");
    }

    @Test
    void throwsWhenFilterExcludesAll() {
        stubInstances("orders", "10.0.0.1:8080");
        CoordLoadBalancer lb = new RoundRobinCoordLoadBalancer(discoveryClient());

        assertThatThrownBy(() -> lb.select("orders", CoordServiceUris.HTTP_CAPABLE.and(i -> false)))
                .isInstanceOf(CoordNoAvailableInstanceException.class);
        // HTTP_CAPABLE 自身：含 address 的实例可选
        CoordServiceInstance selected = lb.select("orders", CoordServiceUris.HTTP_CAPABLE);
        assertThat(selected.getPort()).isEqualTo(8080);
    }
}
