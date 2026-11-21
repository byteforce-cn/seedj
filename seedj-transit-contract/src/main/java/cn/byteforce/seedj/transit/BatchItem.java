package cn.byteforce.seedj.transit;

public final class BatchItem {
    private final String input;
    private final String reference;
    private final String context;
    private final Integer keyVersion;

    public BatchItem(String input, String reference, String context, Integer keyVersion) {
        if (input == null) throw new IllegalArgumentException("input 不能为 null");
        this.input = input;
        this.reference = Validation.requireReference(reference);
        this.context = Validation.normalizeContext(context);
        this.keyVersion = Validation.requirePositiveVersion(keyVersion);
    }

    public String getInput() {
        return input;
    }

    public String getReference() {
        return reference;
    }

    public String getContext() {
        return context;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    @Override
    public String toString() {
        return "BatchItem{reference='" + reference + "', keyVersion=" + keyVersion + "}";
    }
}
