package cn.byteforce.seedj.transit;

import java.util.Collections;
import java.util.Map;

public final class ExportedKey {
    private final KeyType keyType;
    private final boolean containsPrivateKey;
    private final Map<Integer, String> keys;

    public ExportedKey(KeyType keyType, boolean containsPrivateKey, Map<Integer, String> keys) {
        this.keyType = keyType;
        this.containsPrivateKey = containsPrivateKey;
        this.keys = Collections.unmodifiableMap(keys);
    }

    public KeyType getKeyType() {
        return keyType;
    }

    public boolean isContainsPrivateKey() {
        return containsPrivateKey;
    }

    public Map<Integer, String> getKeys() {
        return keys;
    }

    @Override
    public String toString() {
        return "ExportedKey{keyType="
                + keyType
                + ", containsPrivateKey="
                + containsPrivateKey
                + "}";
    }
}
