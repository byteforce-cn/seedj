package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 同一主体的多连接注册与解绑语义。 */
class ChannelRegistryTest {

    private final ChannelRegistry registry = new ChannelRegistry();

    @Test
    @DisplayName("同一 subject 可绑定多条连接")
    void bindsMultipleChannelsPerSubject() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("alice", first);
            registry.bind("alice", second);

            assertThat(registry.channels("alice")).containsExactlyInAnyOrder(first, second);
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("解绑一条连接不影响同一 subject 的另一条")
    void unbindKeepsOtherChannels() {
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        try {
            registry.bind("alice", first);
            registry.bind("alice", second);

            registry.unbind("alice", first);

            assertThat(registry.channels("alice")).containsExactly(second);
            assertThat(registry.size()).isEqualTo(1);
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("并发 bind/unbind 不会把新连接丢进已删除的集合")
    void concurrentUnbindDoesNotOrphanConcurrentBind() throws Exception {
        EmbeddedChannel existing = new EmbeddedChannel();
        CountDownLatch hashing = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        Channel late = blockingChannel(hashing, released);
        ExecutorService bindExecutor = Executors.newSingleThreadExecutor();
        ExecutorService unbindExecutor = Executors.newSingleThreadExecutor();
        try {
            registry.bind("alice", existing);

            Future<?> bindFuture = bindExecutor.submit(() -> registry.bind("alice", late));
            assertThat(hashing.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> unbindFuture =
                    unbindExecutor.submit(() -> registry.unbind("alice", existing));
            try {
                unbindFuture.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException blockedByAtomicRemap) {
                // 修复后 unbind 会在同一 key 的 compute 锁上等待 bind 完成。
            }

            released.countDown();
            bindFuture.get(5, TimeUnit.SECONDS);
            unbindFuture.get(5, TimeUnit.SECONDS);

            assertThat(registry.channels("alice")).containsExactly(late);
        } finally {
            released.countDown();
            bindExecutor.shutdownNow();
            unbindExecutor.shutdownNow();
            existing.finishAndReleaseAll();
        }
    }

    @Test
    @DisplayName("最后一条连接解绑后移除 subject")
    void unbindLastChannelRemovesSubject() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            registry.bind("alice", channel);

            registry.unbind("alice", channel);

            assertThat(registry.channels("alice")).isEmpty();
            assertThat(registry.size()).isZero();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** 在 {@code hashCode()} 上阻塞，用来制造 bind 已取到集合但尚未 add 的窗口。 */
    private static Channel blockingChannel(CountDownLatch hashing, CountDownLatch released) {
        return (Channel)
                Proxy.newProxyInstance(
                        ChannelRegistryTest.class.getClassLoader(),
                        new Class<?>[] {Channel.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "hashCode" -> {
                                        hashing.countDown();
                                        try {
                                            released.await(5, TimeUnit.SECONDS);
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                        }
                                        yield System.identityHashCode(proxy);
                                    }
                                    case "equals" -> proxy == args[0];
                                    case "toString" -> "BlockingChannel";
                                    default -> null;
                                });
    }
}
