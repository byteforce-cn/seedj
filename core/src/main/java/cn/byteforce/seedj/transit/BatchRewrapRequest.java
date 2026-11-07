package cn.byteforce.seedj.transit;

import java.util.List;

public final class BatchRewrapRequest {
    private final String keyName;
    private final List<BatchItem> items;
    private final String globalContext;

    public BatchRewrapRequest(String keyName, List<BatchItem> items, String globalContext) {
        this.keyName = Validation.requireKeyName(keyName);
        this.items = Validation.requireBatchList(items);
        for (BatchItem item : this.items) {
            if (item.getKeyVersion() != null)
                throw new IllegalArgumentException("批量 rewrap 不支持指定 keyVersion");
        }
        this.globalContext = Validation.normalizeContext(globalContext);
    }

    public String getKeyName() {
        return keyName;
    }

    public List<BatchItem> getItems() {
        return items;
    }

    public String getGlobalContext() {
        return globalContext;
    }
}
