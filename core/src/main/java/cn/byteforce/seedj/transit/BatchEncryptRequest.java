package cn.byteforce.seedj.transit;

import java.util.List;

public final class BatchEncryptRequest {
    private final String keyName; private final List<BatchItem> items; private final String globalContext; private final Integer globalKeyVersion;

    public BatchEncryptRequest(String keyName, List<BatchItem> items, String globalContext, Integer globalKeyVersion) {
        this.keyName = Validation.requireKeyName(keyName); this.items = Validation.requireBatchList(items);
        this.globalContext = Validation.normalizeContext(globalContext); this.globalKeyVersion = Validation.requirePositiveVersion(globalKeyVersion);
    }
    public String getKeyName() { return keyName; } public List<BatchItem> getItems() { return items; }
    public String getGlobalContext() { return globalContext; } public Integer getGlobalKeyVersion() { return globalKeyVersion; }
}
