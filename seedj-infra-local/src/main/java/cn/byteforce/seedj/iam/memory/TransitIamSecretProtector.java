package cn.byteforce.seedj.iam.memory;

import cn.byteforce.seedj.iam.spi.IamSecretProtector;
import cn.byteforce.seedj.transit.Ciphertext;
import cn.byteforce.seedj.transit.DecryptRequest;
import cn.byteforce.seedj.transit.EncryptRequest;
import cn.byteforce.seedj.transit.TransitProvider;

import java.util.Objects;

/** Transit-backed protector; uses {@code context} as authenticated data. */
public class TransitIamSecretProtector implements IamSecretProtector {

    private final TransitProvider transit;
    private final String keyName;

    public TransitIamSecretProtector(TransitProvider transit, String keyName) {
        this.transit = Objects.requireNonNull(transit, "transit");
        this.keyName = keyName == null || keyName.isBlank() ? "iam-secrets" : keyName;
    }

    @Override
    public String protect(String plaintext, String context) {
        Ciphertext ciphertext = transit.crypto().encrypt(
                new EncryptRequest(keyName, plaintext, context, null));
        return ciphertext.getValue();
    }

    @Override
    public String reveal(String protectedValue, String context) {
        return transit.crypto().decrypt(new DecryptRequest(keyName, protectedValue, context));
    }

    @Override
    public String keyName() {
        return keyName;
    }
}
