package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.KeyInfo;
import cn.byteforce.seedj.transit.KeyType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 key 的完整状态（不可变）。
 */
record StoredKey(
        KeyType type,
        boolean derived,
        boolean exportable,
        boolean convergentEncryption,
        boolean allowPlaintextBackup,
        int minDecryptionVersion,
        int minEncryptionVersion,
        int latestVersion,
        Map<Integer, KeyMaterial> versions) {

    StoredKey {
        versions = Map.copyOf(versions);
    }

    static StoredKey create(KeyType type, boolean derived, boolean exportable,
                            boolean convergentEncryption, boolean allowPlaintextBackup,
                            int version, KeyMaterial material) {
        return new StoredKey(type, derived, exportable, convergentEncryption,
                allowPlaintextBackup, 1, 1, version, Map.of(version, material));
    }

    StoredKey withNewVersion(KeyMaterial material) {
        int next = latestVersion + 1;
        Map<Integer, KeyMaterial> copy = new LinkedHashMap<>(versions);
        copy.put(next, material);
        return new StoredKey(type, derived, exportable, convergentEncryption,
                allowPlaintextBackup, minDecryptionVersion, minEncryptionVersion, next, copy);
    }

    StoredKey withVersions(int newMinDecryptionVersion, int newMinEncryptionVersion) {
        return new StoredKey(type, derived, exportable, convergentEncryption,
                allowPlaintextBackup,
                newMinDecryptionVersion > 0 ? newMinDecryptionVersion : minDecryptionVersion,
                newMinEncryptionVersion > 0 ? newMinEncryptionVersion : minEncryptionVersion,
                latestVersion, versions);
    }

    KeyInfo toKeyInfo(String name) {
        return new KeyInfo(name, type, latestVersion, minDecryptionVersion, minEncryptionVersion,
                derived, exportable, false, convergentEncryption, allowPlaintextBackup);
    }
}
