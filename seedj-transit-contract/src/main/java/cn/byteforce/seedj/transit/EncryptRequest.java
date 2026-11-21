package cn.byteforce.seedj.transit;

public final class EncryptRequest {
    private final String keyName;
    private final String plaintext;
    private final String context;
    private final Integer keyVersion;

    public EncryptRequest(String keyName, String plaintext, String context, Integer keyVersion) {
        this.keyName = Validation.requireKeyName(keyName);
        if (plaintext == null) throw new IllegalArgumentException("plaintext 不能为 null");
        this.plaintext = plaintext;
        this.context = Validation.normalizeContext(context);
        this.keyVersion = Validation.requirePositiveVersion(keyVersion);
    }

    /** 最常用场景：不指定 context 与 keyVersion。 */
    public static EncryptRequest of(String keyName, String plaintext) {
        return new EncryptRequest(keyName, plaintext, null, null);
    }

    public String getKeyName() {
        return keyName;
    }

    public String getPlaintext() {
        return plaintext;
    }

    public String getContext() {
        return context;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    @Override
    public String toString() {
        return "EncryptRequest{keyName='" + keyName + "'}";
    }
}
