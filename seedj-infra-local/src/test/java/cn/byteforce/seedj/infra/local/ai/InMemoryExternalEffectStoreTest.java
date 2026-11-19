package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.spi.ExternalEffectStore;
import cn.byteforce.seedj.ai.spi.ExternalEffectStoreContractTest;

/** 内存实现跑通外部效果契约。 */
class InMemoryExternalEffectStoreTest extends ExternalEffectStoreContractTest {

    private final InMemoryExternalEffectStore store = new InMemoryExternalEffectStore();

    @Override
    protected ExternalEffectStore store() {
        return store;
    }
}
