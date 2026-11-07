package cn.byteforce.seedj.transit;

public final class MacResult {
    private final String value;
    private final HmacAlgorithm algorithm;
    private final Integer keyVersion;

    public MacResult(String value, HmacAlgorithm algorithm, Integer keyVersion) {
        if (value == null) throw new IllegalArgumentException("value 不能为 null");
        if (algorithm == null) throw new IllegalArgumentException("algorithm 不得为 null");
        this.value = value;
        this.algorithm = algorithm;
        this.keyVersion = keyVersion;
    }

    public String getValue() {
        return value;
    }

    public HmacAlgorithm getAlgorithm() {
        return algorithm;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    @Override
    public String toString() {
        return "MacResult{algorithm=" + algorithm + ", keyVersion=" + keyVersion + "}";
    }
}
