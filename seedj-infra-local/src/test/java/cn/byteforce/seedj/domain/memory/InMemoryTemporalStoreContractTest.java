package cn.byteforce.seedj.domain.memory;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.TemporalStoreContractTest;

/**
 * 内存实现跑公共契约套件。
 */
class InMemoryTemporalStoreContractTest extends TemporalStoreContractTest {

    private final TestClock clock = new TestClock(T0);

    private final InMemoryTemporalStore store = new InMemoryTemporalStore(clock);

    @Override
    protected TemporalStore store() {
        return store;
    }

    @Override
    protected TestClock clock() {
        return clock;
    }

    @Override
    protected void reset() {
        store.clear();
    }
}
