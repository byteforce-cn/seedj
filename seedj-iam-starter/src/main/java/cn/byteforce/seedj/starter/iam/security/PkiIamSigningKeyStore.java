package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.pki.PkiProvider;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Optional PKI adapter. PKI remains a supported key source, but it is no longer required for IAM
 * startup because JDBC/memory keys are the default.
 */
public class PkiIamSigningKeyStore implements IamSigningKeyStore {

    private final IamSigningKey key;

    public PkiIamSigningKeyStore(PkiProvider pki, String alias) {
        PkiProvider.IssuedCertificate issued = pki.signingIdentity(alias);
        RSAPublicKey publicKey = (RSAPublicKey) issued.certificate().getPublicKey();
        RSAPrivateKey privateKey = (RSAPrivateKey) issued.privateKey();
        RSAKey rsaKey =
                new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(issued.serialNumber())
                        .build();
        this.key =
                new IamSigningKey(
                        issued.serialNumber(),
                        "RS256",
                        rsaKey.toPublicJWK().toJSONString(),
                        rsaKey.toJSONString(),
                        true,
                        java.time.Instant.now(),
                        null);
    }

    @Override
    public IamSigningKey activeKey() {
        return key;
    }

    @Override
    public List<IamSigningKey> keys() {
        return List.of(key);
    }

    @Override
    public IamSigningKey rotate() {
        throw new UnsupportedOperationException(
                "PKI signing keys rotate through PkiProvider, not the IAM key store");
    }
}
