package cn.byteforce.seedj.transit;

import java.util.List;

public interface TransitCrypto {
    Ciphertext encrypt(EncryptRequest request);

    String decrypt(DecryptRequest request);

    Ciphertext rewrap(RewrapRequest request);

    List<BatchResult<Ciphertext>> encryptBatch(BatchEncryptRequest request);

    List<BatchResult<String>> decryptBatch(BatchDecryptRequest request);

    List<BatchResult<Ciphertext>> rewrapBatch(BatchRewrapRequest request);

    DataKey generateDataKey(DataKeyRequest request);
}
