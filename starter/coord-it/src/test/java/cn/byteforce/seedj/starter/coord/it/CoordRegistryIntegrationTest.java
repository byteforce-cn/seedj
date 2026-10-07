package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.registry.Registration;
import cn.byteforce.coord.sdk.registry.Registry;
import cn.byteforce.coord.sdk.registry.RegistryEvent;
import cn.byteforce.coord.sdk.registry.ServiceInstance;
import cn.byteforce.coord.sdk.registry.WatchSubscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务注册与发现（{@code Registry}）集成验证。
 *
 * <p>覆盖：注册 → 发现（含 metadata）→ 注销；以及注册事件 Watch。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordRegistryIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private Registry registry;

    @Test
    void registerDiscoverThenDeregister() {
        String service = uniqueName("it-registry");
        String metadata = "{\"addr\":\"127.0.0.1:8080\"}";

        Registration registration = registry.register(service, "i-1", metadata, 30);
        try {
            List<ServiceInstance> found = registry.discover(service);
            assertThat(found).extracting(ServiceInstance::getInstanceId).contains("i-1");
            assertThat(found).filteredOn(instance -> "i-1".equals(instance.getInstanceId()))
                    .extracting(ServiceInstance::getMetadata)
                    .containsExactly(metadata);
        } finally {
            registration.close();
        }

        awaitUntil("注销后实例应不再被发现", Duration.ofSeconds(10),
                () -> registry.discover(service).isEmpty());
    }

    @Test
    void watchReceivesRegistrationEvent() throws InterruptedException {
        String service = uniqueName("it-registry-watch");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RegistryEvent> received = new AtomicReference<>();

        try (WatchSubscription ignored = registry.watch(service, event -> {
            if (event.getType() == RegistryEvent.EventType.INSTANCES_ADDED
                    && !event.getInstances().isEmpty()) {
                received.set(event);
                latch.countDown();
            }
        })) {
            // 订阅流在后台建立：注册（同键重复 = 覆盖续约，幂等）驱动直至事件到达
            for (int i = 0; i < 30 && latch.getCount() > 0; i++) {
                Registration registration = registry.register(service, "i-1", "{}", 30);
                try {
                    latch.await(500, TimeUnit.MILLISECONDS);
                } finally {
                    registration.close();
                }
            }
        }

        assertThat(latch.getCount()).as("15s 内应收到注册事件").isZero();
        assertThat(received.get().getType()).isEqualTo(RegistryEvent.EventType.INSTANCES_ADDED);
        assertThat(received.get().getInstances())
                .extracting(ServiceInstance::getInstanceId)
                .contains("i-1");
    }
}
