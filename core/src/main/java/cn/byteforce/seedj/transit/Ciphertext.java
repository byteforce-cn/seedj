package cn.byteforce.seedj.transit;

public final class Ciphertext {
    private final String value;
    private final Integer keyVersion;
    private final String contextHash;

    public Ciphertext(String value, Integer keyVersion, String contextHash) {
        if (value == null) throw new IllegalArgumentException("value 不能为 null");
        this.value = value;
        this.keyVersion = keyVersion;
        this.contextHash = contextHash;
    }

    public String getValue() {
        return value;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public String getContextHash() {
        return contextHash;
    }

    @Override
    public String toString() {
        return "Ciphertext{keyVersion=" + keyVersion + "}";
    }
}
