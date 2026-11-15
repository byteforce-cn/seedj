package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;

import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStoreContractTest;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakeMqV2Client;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** 契约套件跑在 fake MQ + fake KV 上；真集群语义由 {@link CoordEventConsumerStoreClusterTest} 守护。 */
class CoordEventConsumerStoreContractTest extends EventConsumerStoreContractTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();
    private final FakeMqV2Client mq = new FakeMqV2Client();

    private CoordEventConsumerStore store;

    @Override
    protected EventConsumerStore store() {
        if (store == null) {
            store = newStore(Clock.systemUTC());
        }
        return store;
    }

    @Override
    protected void send(EventMessage message) {
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        headers.put("eventId", message.eventId());
        headers.put("eventType", message.eventType());
        headers.put("eventVersion", Integer.toString(message.eventVersion()));
        headers.put("occurredAt", message.occurredAt().toString());
        mq.send(message.topic(), 0, message.partitionKey(), message.payload(), headers);
    }

    @Override
    protected void reset() {
        if (store != null) {
            store.close();
        }
        store = null;
        backend.clear();
        mq.reset();
    }

    CoordEventConsumerStore newStore(Clock clock) {
        return new CoordEventConsumerStore(
                new CoordKvStore(backend.kv(), backend.txn()),
                mq,
                ENDPOINT,
                CoordKeySpace.of("seedj:test", "event-consumer"),
                "member-test",
                0,
                1_000,
                1,
                clock);
    }
}
