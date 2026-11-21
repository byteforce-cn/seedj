package cn.byteforce.seedj.transit;

public interface TransitKeyAdmin {
    KeyInfo createKey(CreateKeyRequest request);

    KeyInfo readKey(String keyName);

    void rotateKey(String keyName);

    void configureKey(String keyName, KeyConfigRequest request);

    void deleteKey(String keyName);

    ExportedKey exportKey(String keyName, KeyType type, Integer version);
}
