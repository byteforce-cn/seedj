package cn.byteforce.seedj.transit;

public final class VerifyRequest {
    private final String keyName; private final String data; private final String signature; private final SignatureAlgorithm algorithm; private final Integer keyVersion;

    public VerifyRequest(String keyName, String data, String signature, SignatureAlgorithm algorithm, Integer keyVersion) {
        this.keyName = Validation.requireKeyName(keyName);
        if (data == null) throw new IllegalArgumentException("data 不能为 null");
        this.data = data; this.signature = Validation.requireBase64NonEmpty(signature, "signature");
        this.algorithm = algorithm; this.keyVersion = Validation.requirePositiveVersion(keyVersion);
    }
    public String getKeyName() { return keyName; } public String getData() { return data; } public String getSignature() { return signature; }
    public SignatureAlgorithm getAlgorithm() { return algorithm; } public Integer getKeyVersion() { return keyVersion; }
}
