package cn.byteforce.seedj.transit;

public final class VerifyHmacRequest {
    private final String keyName;
    private final String data;
    private final String hmac;
    private final HmacAlgorithm algorithm;
    private final Integer keyVersion;

    public VerifyHmacRequest(
            String keyName, String data, String hmac, HmacAlgorithm algorithm, Integer keyVersion) {
        this.keyName = Validation.requireKeyName(keyName);
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        this.data = data;
        this.hmac = Validation.requireBase64NonEmpty(hmac, "hmac");
        this.algorithm = algorithm;
        this.keyVersion = Validation.requirePositiveVersion(keyVersion);
    }

    public String getKeyName() {
        return keyName;
    }

    public String getData() {
        return data;
    }

    public String getHmac() {
        return hmac;
    }

    public HmacAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }
}
