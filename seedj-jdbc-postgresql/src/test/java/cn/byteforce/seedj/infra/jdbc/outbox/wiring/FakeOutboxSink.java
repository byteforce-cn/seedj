package cn.byteforce.seedj.infra.jdbc.outbox.wiring;

import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.OutboxSink;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * relay 测试用的可控 sink。
 */
final class FakeOutboxSink implements OutboxSink {

    final List<OutboxRecord> delivered = new CopyOnWriteArrayList<>();
    volatile Function<OutboxRecord, CompletionStage<Void>> behavior =
            record -> CompletableFuture.completedFuture(null);

    @Override
    public CompletionStage<Void> deliver(OutboxRecord record) {
        delivered.add(record);
        return behavior.apply(record);
    }
}
