package cn.byteforce.seedj.infra.local.im;

import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImCardActionStoreContractTest;

/** 内存实现跑通卡片动作契约。 */
class InMemoryImCardActionStoreTest extends ImCardActionStoreContractTest {

    private final InMemoryImCardActionStore store = new InMemoryImCardActionStore();

    @Override
    protected ImCardActionStore store() {
        return store;
    }
}
