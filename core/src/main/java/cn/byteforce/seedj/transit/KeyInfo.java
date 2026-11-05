package cn.byteforce.seedj.transit;

public final class KeyInfo {
    private final String name; private final KeyType type; private final int latestVersion; private final int minDecryptionVersion;
    private final int minEncryptionVersion; private final boolean derived; private final boolean exportable; private final boolean deletionAllowed;
    private final boolean convergentEncryption; private final boolean allowPlaintextBackup;

    public KeyInfo(String name, KeyType type, int latestVersion, int minDecryptionVersion, int minEncryptionVersion, boolean derived, boolean exportable, boolean deletionAllowed, boolean convergentEncryption, boolean allowPlaintextBackup) {
        this.name = name; this.type = type; this.latestVersion = latestVersion; this.minDecryptionVersion = minDecryptionVersion;
        this.minEncryptionVersion = minEncryptionVersion; this.derived = derived; this.exportable = exportable;
        this.deletionAllowed = deletionAllowed; this.convergentEncryption = convergentEncryption; this.allowPlaintextBackup = allowPlaintextBackup;
    }
    public String getName() { return name; } public KeyType getType() { return type; } public int getLatestVersion() { return latestVersion; }
    public int getMinDecryptionVersion() { return minDecryptionVersion; } public int getMinEncryptionVersion() { return minEncryptionVersion; }
    public boolean isDerived() { return derived; } public boolean isExportable() { return exportable; } public boolean isDeletionAllowed() { return deletionAllowed; }
    public boolean isConvergentEncryption() { return convergentEncryption; } public boolean isAllowPlaintextBackup() { return allowPlaintextBackup; }
    @Override public String toString() { return "KeyInfo{name='" + name + "', type=" + type + "}"; }
}
