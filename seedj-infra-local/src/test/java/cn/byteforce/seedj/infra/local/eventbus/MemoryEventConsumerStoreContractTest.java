package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStoreContractTest;

class MemoryEventConsumerStoreContractTest extends EventConsumerStoreContractTest {

    private final MemoryEventBusTransport transport = new MemoryEventBusTransport(10_000);

    @Override
    protected EventConsumerStore store() {
        return transport;
    }

    @Override
    protected void send(EventMessage message) {
        ((EventBusTransport) transport).send(message).toCompletableFuture().join();
    }

    @Override
    protected void reset() {
        // 每个测试实例新建 transport，无需清理
    }
}
