package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** 记录 send 调用的测试 transport。 */
final class RecordingTransport implements EventBusTransport {

    final List<EventMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<Void> send(EventMessage message) {
        sent.add(message);
        return CompletableFuture.completedFuture(null);
    }
}
