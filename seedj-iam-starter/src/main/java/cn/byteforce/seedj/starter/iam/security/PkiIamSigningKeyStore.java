package cn.byteforce.seedj.starter.iam.security;

import cn.byteforce.seedj.iam.IamSigningKey;
import cn.byteforce.seedj.iam.spi.IamSigningKeyStore;
import cn.byteforce.seedj.provider.pki.IssuedCertificate;
import cn.byteforce.seedj.provider.pki.KeyMaterial;
import cn.byteforce.seedj.provider.pki.PkiProvider;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Optional PKI adapter. PKI remains a supported key source, but it is no longer required for IAM
 * startup because JDBC/memory keys are the default.
 *
 * <p>IAM 需要可导出的 RSA 私钥来构造 RS256 JWK，所以只接受 {@link KeyMaterial.External}。
 * coord 托管私钥（{@link KeyMaterial.CoordManaged}）不能当 IAM 签名钥：它只能远程签名，不能导出成 JWK。
 */
public class PkiIamSigningKeyStore implements IamSigningKeyStore {

    private final IamSigningKey key;

    public PkiIamSigningKeyStore(PkiProvider pki, String alias) {
        IssuedCertificate issued = pki.signingIdentity(alias);
        if (!(issued.keyMaterial() instanceof KeyMaterial.External external)
                || !external.attached()) {
            throw new IllegalStateException(
                    "seedj.iam.jwk.provider=pki requires an exportable PKI private key; "
                            + "coord-managed keys cannot back IAM RS256 JWKs. "
                            + "Use the local PKI provider or the default JDBC/memory JWK store");
        }
        if (!(issued.certificate().getPublicKey() instanceof RSAPublicKey publicKey)
                || !(external.privateKey() instanceof RSAPrivateKey privateKey)) {
            throw new IllegalStateException(
                    "seedj.iam.jwk.provider=pki requires an RSA signing identity, got "
                            + issued.certificate().getPublicKey().getAlgorithm());
        }
        String keyId = issued.serialNumber().toString(16);
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
        this.key =
                new IamSigningKey(
                        keyId,
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
