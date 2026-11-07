package cn.byteforce.seedj.transit;

public final class KeyConfigRequest {
    private final Integer minDecryptionVersion;
    private final Integer minEncryptionVersion;
    private final Boolean deletionAllowed;

    public KeyConfigRequest(
            Integer minDecryptionVersion, Integer minEncryptionVersion, Boolean deletionAllowed) {
        if (minDecryptionVersion == null && minEncryptionVersion == null && deletionAllowed == null)
            throw new IllegalArgumentException("至少提供一个配置项");
        if (minDecryptionVersion != null && minDecryptionVersion < 1)
            throw new IllegalArgumentException("版本号必须 >= 1");
        if (minEncryptionVersion != null && minEncryptionVersion < 1)
            throw new IllegalArgumentException("版本号必须 >= 1");
        this.minDecryptionVersion = minDecryptionVersion;
        this.minEncryptionVersion = minEncryptionVersion;
        this.deletionAllowed = deletionAllowed;
    }

    public Integer getMinDecryptionVersion() {
        return minDecryptionVersion;
    }

    public Integer getMinEncryptionVersion() {
        return minEncryptionVersion;
    }

    public Boolean getDeletionAllowed() {
        return deletionAllowed;
    }
}
