package cn.byteforce.seedj.starter.coord.registry;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordDiscoveryClient} 缓存与陈旧窗口降级语义测试（约定 C6）。
 */
class CoordDiscoveryClientTest {

    private final Registry registry = mock(Registry.class);
    private final MutableClock clock = new MutableClock();

    private CoordDiscoveryClient client(CoordRegistryProperties.Discovery discovery) {
        return new CoordDiscoveryClient(registry, discovery, clock);
    }

    private static CoordRegistryProperties.Discovery discovery(boolean cacheEnabled,
                                                               Duration cacheTtl, Duration maxStale) {
        CoordRegistryProperties.Discovery discovery = new CoordRegistryProperties.Discovery();
        discovery.setCacheEnabled(cacheEnabled);
        discovery.setCacheTtl(cacheTtl);
        discovery.setMaxStale(maxStale);
        return discovery;
    }

    @Test
    void cachesWithinTtlAndConvertsInstances() {
        when(registry.discover("orders")).thenReturn(List.of(
                new ServiceInstance("i-1", "orders", "{\"address\":\"10.0.0.1:8081\"}")));
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        List<CoordServiceInstance> first = client.getInstances("orders");
        List<CoordServiceInstance> second = client.getInstances("orders");

        verify(registry, times(1)).discover("orders");
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getPort()).isEqualTo(8081);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void refreshesAfterTtl() {
        when(registry.discover("orders")).thenReturn(List.of());
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        client.getInstances("orders");
        clock.advance(Duration.ofSeconds(31));
        client.getInstances("orders");

        verify(registry, times(2)).discover("orders");
    }

    @Test
    void servesStaleWhenAgentUnreachableWithinMaxStale() {
        when(registry.discover("orders"))
                .thenReturn(List.of(new ServiceInstance("i-1", "orders", "{}")))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"));
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        client.getInstances("orders");
        clock.advance(Duration.ofMinutes(1));
        List<CoordServiceInstance> degraded = client.getInstances("orders");

        assertThat(degraded).extracting(CoordServiceInstance::getInstanceId).containsExactly("i-1");
    }

    @Test
    void throwsWhenBeyondMaxStale() {
        when(registry.discover("orders"))
                .thenReturn(List.of())
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"));
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(1)));

        client.getInstances("orders");
        clock.advance(Duration.ofMinutes(2));

        assertThatThrownBy(() -> client.getInstances("orders"))
                .isInstanceOf(CoordException.class)
                .hasMessageContaining("agent 不可达");
    }

    @Test
    void throwsWithoutCacheEntryWhenAgentUnreachable() {
        when(registry.discover(anyString()))
                .thenThrow(new CoordException(ErrorCode.AGENT_UNAVAILABLE, "agent 不可达"));
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        assertThatThrownBy(() -> client.getInstances("orders")).isInstanceOf(CoordException.class);
    }

    @Test
    void cacheDisabledAlwaysFetches() {
        when(registry.discover("orders")).thenReturn(List.of());
        CoordDiscoveryClient client = client(discovery(false, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        client.getInstances("orders");
        client.getInstances("orders");

        verify(registry, times(2)).discover("orders");
    }

    @Test
    void invalidateForcesRefetch() {
        when(registry.discover("orders")).thenReturn(List.of());
        CoordDiscoveryClient client = client(discovery(true, Duration.ofSeconds(30), Duration.ofMinutes(5)));

        client.getInstances("orders");
        client.invalidate("orders");
        client.getInstances("orders");
        client.invalidateAll();
        client.getInstances("orders");

        verify(registry, times(3)).discover("orders");
    }

    /** 可手动推进的时钟（测试确定性，避免 sleep）。 */
    static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-10-07T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
