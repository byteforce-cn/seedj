package cn.byteforce.seedj.transit;

public final class DecryptRequest {
    private final String keyName; private final String ciphertext; private final String context;

    public DecryptRequest(String keyName, String ciphertext, String context) {
        this.keyName = Validation.requireKeyName(keyName);
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext 不能为 null");
        this.ciphertext = ciphertext; this.context = Validation.normalizeContext(context);
    }
    /** 最常用场景：不指定 context。 */
    public static DecryptRequest of(String keyName, String ciphertext) {
        return new DecryptRequest(keyName, ciphertext, null);
    }

    public String getKeyName() { return keyName; } public String getCiphertext() { return ciphertext; } public String getContext() { return context; }
    @Override public String toString() { return "DecryptRequest{keyName='" + keyName + "'}"; }
}
