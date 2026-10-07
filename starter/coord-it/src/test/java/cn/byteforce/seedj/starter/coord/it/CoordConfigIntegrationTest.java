package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.config.ConfigClient;
import cn.byteforce.coord.sdk.config.ConfigEvent;
import cn.byteforce.coord.sdk.config.ConfigWatchSubscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式配置（{@code ConfigClient}）集成验证。
 *
 * <p>覆盖：写入 / 类型化读取 / 前缀列举 / Watch 变更事件（经 starter Bean 注入，
 * 走真实 Agent gRPC）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordConfigIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private ConfigClient configClient;

    @Test
    void putThenGetAndListRoundTrip() {
        String prefix = "it/config/" + uniqueName("cfg") + "/";
        configClient.put(prefix + "hello", "world");
        configClient.put(prefix + "count", "42");
        configClient.put(prefix + "flag", "true");

        assertThat(configClient.getString(prefix + "hello")).contains("world");
        assertThat(configClient.getInt(prefix + "count")).contains(42);
        assertThat(configClient.getBoolean(prefix + "flag")).contains(true);

        assertThat(configClient.list(prefix))
                .containsEntry(prefix + "hello", "world")
                .containsEntry(prefix + "count", "42")
                .containsEntry(prefix + "flag", "true");
    }

    @Test
    void watchReceivesPutEvent() throws InterruptedException {
        String prefix = "it/watch/" + uniqueName("cfg") + "/";
        String key = prefix + "key";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ConfigEvent> received = new AtomicReference<>();
        try (ConfigWatchSubscription ignored = configClient.watch(prefix, event -> {
            received.set(event);
            latch.countDown();
        })) {
            // watch 的流在后台虚拟线程建立：以重复写入驱动直至首个事件到达，
            // 消除「订阅建立竞态」导致的偶发漏事件（不是放宽断言——见下方 value 断言）。
            for (int i = 0; i < 30 && latch.getCount() > 0; i++) {
                configClient.put(key, "v" + i);
                latch.await(500, TimeUnit.MILLISECONDS);
            }
        }

        assertThat(latch.getCount()).as("15s 内应收到 watch 事件").isZero();
        assertThat(received.get().getKey()).isEqualTo(key);
        assertThat(received.get().getNewValue().orElse("")).startsWith("v");
        assertThat(received.get().getRevision()).isPositive();
    }

    @Test
    void roundTripsUnicodeValues() {
        String prefix = "it/config/" + uniqueName("utf8") + "/";
        String text = "你好，coord";
        configClient.put(prefix + "text", text);
        assertThat(configClient.getString(prefix + "text")).contains(text);
    }
}
