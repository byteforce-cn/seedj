package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransportContractTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEventBusTransportContractTest extends EventBusTransportContractTest {

    @Override
    protected MemoryEventBusTransport newTransport() {
        return new MemoryEventBusTransport(100);
    }

    @Test
    @DisplayName("有界队列丢弃最旧记录并计数")
    void boundedQueueDropsOldest() {
        MemoryEventBusTransport transport = new MemoryEventBusTransport(2);

        for (int i = 0; i < 5; i++) {
            transport.send(EventMessage.of("e-" + i, "topic", "k", ("p" + i).getBytes(StandardCharsets.UTF_8)))
                    .toCompletableFuture().join();
        }

        assertThat(transport.size()).isEqualTo(2);
        assertThat(transport.droppedCount()).isEqualTo(3);
        List<EventMessage> snapshot = transport.snapshot();
        assertThat(new String(snapshot.get(1).payload(), StandardCharsets.UTF_8)).isEqualTo("p4");
    }

    @Test
    @DisplayName("snapshot 是快照，不暴露内部队列")
    void snapshotIsIsolated() {
        MemoryEventBusTransport transport = new MemoryEventBusTransport(10);
        transport.send(EventMessage.of("e-1", "t", "k", new byte[0])).toCompletableFuture().join();

        List<EventMessage> snapshot = transport.snapshot();
        assertThat(snapshot).hasSize(1);
        assertThat(transport.size()).isEqualTo(1);
    }
}
