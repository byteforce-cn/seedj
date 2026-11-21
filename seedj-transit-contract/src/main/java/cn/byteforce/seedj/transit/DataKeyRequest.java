package cn.byteforce.seedj.transit;

public final class DataKeyRequest {
    private final String keyName;
    private final String context;
    private final Integer keyVersion;
    private final boolean withPlaintext;

    public DataKeyRequest(
            String keyName, String context, Integer keyVersion, boolean withPlaintext) {
        this.keyName = Validation.requireKeyName(keyName);
        this.context = Validation.normalizeContext(context);
        this.keyVersion = Validation.requirePositiveVersion(keyVersion);
        this.withPlaintext = withPlaintext;
    }

    public String getKeyName() {
        return keyName;
    }

    public String getContext() {
        return context;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public boolean isWithPlaintext() {
        return withPlaintext;
    }
}
