package cn.byteforce.seedj.starter.coord.config;

import cn.byteforce.coord.sdk.config.ConfigClient;
import cn.byteforce.coord.sdk.config.ConfigEvent;
import cn.byteforce.coord.sdk.config.ConfigListener;
import cn.byteforce.coord.sdk.config.ConfigWatchSubscription;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CoordConfigRefreshManager} 运行期刷新语义测试：
 * 订阅 → 快照合并 → 事件应用（含删除 / 幂等）→ 防抖 rebind → 事件发布 → 断流重订阅。
 */
class CoordConfigRefreshManagerTest {

    private final ConfigClient configClient = mock(ConfigClient.class);
    private final CoordConfigurationPropertiesRebinder rebinder = mock(CoordConfigurationPropertiesRebinder.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher publisher = event -> {
        if (event instanceof ApplicationEvent applicationEvent) {
            events.add(applicationEvent);
        }
    };

    private CoordConfigRefreshManager manager() {
        return new CoordConfigRefreshManager(configClient, rebinder, environment, publisher,
                Duration.ofMillis(20));
    }

    private static ConfigWatchSubscription subscription() {
        return mock(ConfigWatchSubscription.class);
    }

    @Test
    void startSubscribesEachPrefixAndMergesSnapshot() {
        when(configClient.watch(eq("app.orders"), any())).thenReturn(subscription());
        when(configClient.watch(eq("app.common"), any())).thenReturn(subscription());
        when(configClient.list("app.orders")).thenReturn(Map.of("app.orders.timeout", "5s"));
        when(configClient.list("app.common")).thenReturn(Map.of("app.common.flag", "on"));
        CoordPropertySource source = CoordPropertySource.forPrefixes(List.of("app.orders", "app.common"));
        environment.getPropertySources().addLast(source);

        CoordConfigRefreshManager manager = manager();
        manager.start();
        try {
            assertThat(source.getProperty("app.orders.timeout")).isEqualTo("5s");
            assertThat(source.getProperty("app.common.flag")).isEqualTo("on");
            awaitUntil(() -> manager.subscribedPrefixes().size() == 2);
            // rebind 与事件发布在调度线程上先后执行：仅等待 rebind 计数不能保证事件已入列，
            // 必须等待事件本身出现（2026-10-07 flake 根因：读取事件列表与事件发布的时序竞争）。
            awaitUntil(() -> rebindCalls() == 1 && events.size() == 1);
            CoordConfigRefreshedEvent event = (CoordConfigRefreshedEvent) events.get(0);
            assertThat(event.getChangedKeys()).contains("app.orders.timeout", "app.common.flag");
            assertThat(event.hasRebindFailures()).isFalse();
        } finally {
            manager.stop();
        }
    }

    @Test
    void appliesEventsAndCoalescesRebind() {
        ConfigWatchSubscription handle = subscription();
        when(configClient.watch(eq("app.orders"), any())).thenReturn(handle);
        when(configClient.list("app.orders")).thenReturn(Map.of());
        CoordPropertySource source = CoordPropertySource.forPrefixes(List.of("app.orders"));
        environment.getPropertySources().addLast(source);
        CoordConfigRefreshManager manager = manager();
        manager.start();

        ArgumentCaptor<ConfigListener> listenerCaptor = ArgumentCaptor.forClass(ConfigListener.class);
        verify(configClient).watch(eq("app.orders"), listenerCaptor.capture());
        ConfigListener listener = listenerCaptor.getValue();

        // 同一防抖窗口内两条事件 → 合并为一次 rebind
        listener.onEvent(new ConfigEvent("app.orders.a", Optional.of("1"), 1));
        listener.onEvent(new ConfigEvent("app.orders.b", Optional.of("2"), 2));

        awaitUntil(() -> rebindCalls() >= 1);
        assertThat(source.getProperty("app.orders.a")).isEqualTo("1");
        assertThat(source.getProperty("app.orders.b")).isEqualTo("2");
        sleep(300);
        assertThat(rebindCalls()).isEqualTo(1);

        // 幂等：重复事件不产生变更 / 不触发额外 rebind
        listener.onEvent(new ConfigEvent("app.orders.a", Optional.of("1"), 3));
        sleep(300);
        assertThat(rebindCalls()).isEqualTo(1);

        // 删除事件（new_value 缺省 = 删除，C2 暂定语义）
        listener.onEvent(new ConfigEvent("app.orders.a", Optional.empty(), 4));
        awaitUntil(() -> source.getProperty("app.orders.a") == null && rebindCalls() >= 2);
        assertThat(source.getProperty("app.orders.b")).isEqualTo("2");

        manager.stop();
        verify(handle).close();
    }

    @Test
    void terminatesAndResubscribes() {
        ConfigWatchSubscription first = subscription();
        ConfigWatchSubscription second = subscription();
        java.util.concurrent.atomic.AtomicInteger watches = new java.util.concurrent.atomic.AtomicInteger();
        when(configClient.watch(eq("app.orders"), any(ConfigListener.class)))
                .thenAnswer(invocation -> watches.incrementAndGet() == 1 ? first : second);
        when(configClient.list("app.orders")).thenReturn(Map.of());
        CoordPropertySource source = CoordPropertySource.forPrefixes(List.of("app.orders"));
        environment.getPropertySources().addLast(source);
        CoordConfigRefreshManager manager = manager();
        manager.start();

        ArgumentCaptor<ConfigListener> listenerCaptor = ArgumentCaptor.forClass(ConfigListener.class);
        verify(configClient).watch(eq("app.orders"), listenerCaptor.capture());
        listenerCaptor.getValue().onTerminated(new RuntimeException("stream reset"));

        awaitUntil(() -> watches.get() >= 2);
        manager.stop();
        verify(first).close();
        verify(second).close();
    }

    @Test
    void stopClosesSubscriptionsAndStopsReacting() {
        ConfigWatchSubscription handle = subscription();
        when(configClient.watch(eq("app.orders"), any())).thenReturn(handle);
        when(configClient.list("app.orders")).thenReturn(Map.of());
        CoordPropertySource source = CoordPropertySource.forPrefixes(List.of("app.orders"));
        environment.getPropertySources().addLast(source);
        CoordConfigRefreshManager manager = manager();
        manager.start();
        manager.stop();

        assertThat(manager.isRunning()).isFalse();
        verify(handle).close();
        assertThat(manager.subscribedPrefixes()).isEmpty();
    }

    @Test
    void withoutImportNoSubscription() {
        CoordConfigRefreshManager manager = manager();
        manager.start();
        try {
            assertThat(manager.subscribedPrefixes()).isEmpty();
            verify(configClient, times(0)).watch(any(), any());
        } finally {
            manager.stop();
        }
    }

    @Test
    void resubscribeConvergesDeletionsSeenWhileDisconnected() {
        ConfigWatchSubscription first = subscription();
        ConfigWatchSubscription second = subscription();
        when(configClient.watch(eq("app.orders"), any())).thenReturn(first, second);
        when(configClient.list("app.orders"))
                .thenReturn(Map.of("app.orders.a", "1", "app.orders.b", "2"))
                .thenReturn(Map.of("app.orders.b", "2"));
        CoordPropertySource source = CoordPropertySource.forPrefixes(List.of("app.orders"));
        environment.getPropertySources().addLast(source);
        CoordConfigRefreshManager manager = manager();
        manager.start();
        awaitUntil(() -> source.getProperty("app.orders.a") != null);

        ArgumentCaptor<ConfigListener> listenerCaptor = ArgumentCaptor.forClass(ConfigListener.class);
        verify(configClient).watch(eq("app.orders"), listenerCaptor.capture());
        listenerCaptor.getValue().onTerminated(new RuntimeException("down"));

        // 断线期间删除的 a：重订阅后按快照收敛移除
        awaitUntil(() -> source.getProperty("app.orders.a") == null);
        assertThat(source.getProperty("app.orders.b")).isEqualTo("2");
        manager.stop();
    }

    private int rebindCalls() {
        return (int) mockingDetails(rebinder).getInvocations().stream()
                .filter(invocation -> "rebindAll".equals(invocation.getMethod().getName()))
                .count();
    }


    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10);
        }
        throw new AssertionError("等待超时（10s）");
    }
}
