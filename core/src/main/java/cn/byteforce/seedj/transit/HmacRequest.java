package cn.byteforce.seedj.transit;

public final class HmacRequest {
    private final String keyName;
    private final String data;
    private final HmacAlgorithm algorithm;
    private final Integer keyVersion;

    public HmacRequest(String keyName, String data, HmacAlgorithm algorithm, Integer keyVersion) {
        this.keyName = Validation.requireKeyName(keyName);
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        this.data = data;
        this.algorithm = algorithm;
        this.keyVersion = Validation.requirePositiveVersion(keyVersion);
    }

    public String getKeyName() {
        return keyName;
    }

    public String getData() {
        return data;
    }

    public HmacAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }
}
