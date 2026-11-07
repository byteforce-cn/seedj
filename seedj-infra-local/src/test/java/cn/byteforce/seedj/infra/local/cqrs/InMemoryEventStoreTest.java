package cn.byteforce.seedj.infra.local.cqrs;

import cn.byteforce.seedj.cqrs.IdCodecs;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.EventStoreContractTest;

/** {@link InMemoryEventStore} 对 {@link EventStore} 契约的实现验证。 */
class InMemoryEventStoreTest extends EventStoreContractTest {

    @Override
    protected EventStore<String> newStore() {
        return new InMemoryEventStore<>(IdCodecs.strings());
    }
}
