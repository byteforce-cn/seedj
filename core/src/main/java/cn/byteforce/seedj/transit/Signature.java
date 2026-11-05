package cn.byteforce.seedj.transit;

public final class Signature {
    private final String value; private final SignatureAlgorithm algorithm; private final Integer keyVersion;

    public Signature(String value, SignatureAlgorithm algorithm, Integer keyVersion) {
        if (value == null) throw new IllegalArgumentException("value 不能为 null");
        if (algorithm == null) throw new IllegalArgumentException("algorithm 不得为 null，必须回填实际使用的算法");
        this.value = value; this.algorithm = algorithm; this.keyVersion = keyVersion;
    }
    public String getValue() { return value; } public SignatureAlgorithm getAlgorithm() { return algorithm; } public Integer getKeyVersion() { return keyVersion; }
    @Override public String toString() { return "Signature{algorithm=" + algorithm + ", keyVersion=" + keyVersion + "}"; }
}
