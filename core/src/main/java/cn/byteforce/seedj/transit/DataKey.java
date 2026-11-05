package cn.byteforce.seedj.transit;

import java.util.Arrays;

public final class DataKey {
    private byte[] plaintextKey;
    private final Ciphertext ciphertextKey;

    public DataKey(byte[] plaintextKey, Ciphertext ciphertextKey) {
        this.plaintextKey = plaintextKey == null ? null : plaintextKey.clone();
        this.ciphertextKey = ciphertextKey;
    }

    public boolean hasPlaintext() {
        return plaintextKey != null;
    }

    public byte[] usePlaintextKey() {
        if (plaintextKey == null) {
            throw new IllegalStateException("明文密钥不可用或已销毁");
        }
        return plaintextKey.clone();
    }

    public Ciphertext getCiphertextKey() {
        return ciphertextKey;
    }

    public void destroy() {
        if (plaintextKey != null) {
            Arrays.fill(plaintextKey, (byte) 0);
            plaintextKey = null;
        }
    }
}