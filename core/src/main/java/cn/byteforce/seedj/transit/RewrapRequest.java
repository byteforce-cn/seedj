package cn.byteforce.seedj.transit;

public final class RewrapRequest {
    private final String keyName;
    private final String ciphertext;
    private final String context;

    public RewrapRequest(String keyName, String ciphertext, String context) {
        this.keyName = Validation.requireKeyName(keyName);
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext 不能为 null");
        this.ciphertext = ciphertext;
        this.context = Validation.normalizeContext(context);
    }

    public String getKeyName() {
        return keyName;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public String getContext() {
        return context;
    }
}
