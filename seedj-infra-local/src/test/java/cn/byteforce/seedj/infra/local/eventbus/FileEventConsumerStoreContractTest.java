package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStoreContractTest;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileEventConsumerStoreContractTest extends EventConsumerStoreContractTest {

    @TempDir
    Path tempDir;

    private FileEventBusTransport transport;

    @Override
    protected EventConsumerStore store() {
        if (transport == null) {
            transport = new FileEventBusTransport(tempDir);
        }
        return transport;
    }

    @Override
    protected void send(EventMessage message) {
        ((EventBusTransport) store()).send(message).toCompletableFuture().join();
    }

    @Override
    protected void reset() {
        // @TempDir 每个测试实例独立，无需清理
    }
}
