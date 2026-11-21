package cn.byteforce.seedj.transit;

public final class CreateKeyRequest {
    private final String keyName;
    private final KeyType type;
    private final Boolean derived;
    private final Boolean convergentEncryption;
    private final Boolean exportable;
    private final Boolean allowPlaintextBackup;

    public CreateKeyRequest(
            String keyName,
            KeyType type,
            Boolean derived,
            Boolean convergentEncryption,
            Boolean exportable,
            Boolean allowPlaintextBackup) {
        this.keyName = Validation.requireKeyName(keyName);
        if (type == null) throw new IllegalArgumentException("type 不能为 null");
        this.type = type;
        this.derived = derived;
        this.convergentEncryption = convergentEncryption;
        this.exportable = exportable;
        this.allowPlaintextBackup = allowPlaintextBackup;
    }

    /** 最常用场景：只指定名称与类型，其余走 provider 默认值。 */
    public static CreateKeyRequest of(String keyName, KeyType type) {
        return new CreateKeyRequest(keyName, type, null, null, null, null);
    }

    public String getKeyName() {
        return keyName;
    }

    public KeyType getType() {
        return type;
    }

    public Boolean getDerived() {
        return derived;
    }

    public Boolean getConvergentEncryption() {
        return convergentEncryption;
    }

    public Boolean getExportable() {
        return exportable;
    }

    public Boolean getAllowPlaintextBackup() {
        return allowPlaintextBackup;
    }

    @Override
    public String toString() {
        return "CreateKeyRequest{keyName='" + keyName + "'}";
    }
}
