package cn.byteforce.seedj.transit;

public final class SignRequest {
    private final String keyName;
    private final String data;
    private final SignatureAlgorithm algorithm;
    private final Integer keyVersion;

    public SignRequest(
            String keyName, String data, SignatureAlgorithm algorithm, Integer keyVersion) {
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

    public SignatureAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    @Override
    public String toString() {
        return "SignRequest{keyName='" + keyName + "'}";
    }
}
