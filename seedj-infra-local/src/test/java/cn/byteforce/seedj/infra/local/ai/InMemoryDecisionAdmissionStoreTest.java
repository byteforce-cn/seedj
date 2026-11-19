package cn.byteforce.seedj.infra.local.ai;

import cn.byteforce.seedj.ai.spi.DecisionAdmissionStore;
import cn.byteforce.seedj.ai.spi.DecisionAdmissionStoreContractTest;

/** 内存实现跑通准入契约。 */
class InMemoryDecisionAdmissionStoreTest extends DecisionAdmissionStoreContractTest {

    private final InMemoryDecisionAdmissionStore store = new InMemoryDecisionAdmissionStore();

    @Override
    protected DecisionAdmissionStore store() {
        return store;
    }
}
