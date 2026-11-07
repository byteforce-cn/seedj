package cn.byteforce.seedj.eventbus.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.eventbus.EventMessage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EventBusTransport 契约测试套件。 */
public abstract class EventBusTransportContractTest {

    protected EventBusTransport transport;

    protected abstract EventBusTransport newTransport();

    @BeforeEach
    void create() {
        transport = newTransport();
    }

    @AfterEach
    void close() {
        if (transport != null) {
            transport.close();
        }
    }

    protected static EventMessage message(String eventId) {
        return EventMessage.of(
                eventId, "topic", "key", ("payload-" + eventId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("send 正常完成")
    void sendCompletes() {
        assertThat(transport.send(message("e1")).toCompletableFuture())
                .succeedsWithin(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("close 幂等")
    void closeIsIdempotent() {
        transport.close();
        transport.close();
    }

    @Test
    @DisplayName("关闭后 send 失败（不静默丢弃）")
    void sendAfterCloseFails() {
        transport.close();

        assertThatThrownBy(() -> transport.send(message("e2")).toCompletableFuture().get())
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("传输不修改消息")
    void messageUnchanged() {
        EventMessage message = message("e3");
        byte[] before = message.payload();

        transport.send(message).toCompletableFuture().join();

        assertThat(message.payload()).isEqualTo(before);
    }
}
