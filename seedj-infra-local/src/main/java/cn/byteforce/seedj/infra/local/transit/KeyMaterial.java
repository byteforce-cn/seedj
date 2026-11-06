package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.TransitErrorCode;
import cn.byteforce.seedj.transit.TransitException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

/**
 * 与存储格式无关的密钥材料表示。
 *
 * <p>对称密钥只填 {@code secret}；非对称密钥对填 {@code privateKey}/{@code publicKey}。
 * {@code algorithm} 是 JCE 算法名，用于重建 {@link SecretKey} / {@link KeyPair}。
 */
record KeyMaterial(byte[] secret, byte[] privateKey, byte[] publicKey, String algorithm) {

    KeyMaterial {
        Objects.requireNonNull(algorithm, "algorithm");
        boolean symmetric = secret != null;
        boolean asymmetric = privateKey != null && publicKey != null;
        if (symmetric == asymmetric) {
            throw new IllegalArgumentException("exactly one of secret / keypair must be present");
        }
    }

    static KeyMaterial of(SecretKey key) {
        return new KeyMaterial(key.getEncoded(), null, null, key.getAlgorithm());
    }

    static KeyMaterial of(KeyPair keyPair, String algorithm) {
        return new KeyMaterial(null,
                keyPair.getPrivate().getEncoded(),
                keyPair.getPublic().getEncoded(),
                algorithm);
    }

    boolean isSymmetric() {
        return secret != null;
    }

    SecretKey toSecretKey() {
        return new SecretKeySpec(secret, algorithm);
    }

    KeyPair toKeyPair() {
        try {
            KeyFactory factory = KeyFactory.getInstance(algorithm);
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(privateKey));
            PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(publicKey));
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            throw new TransitException(TransitErrorCode.INVALID_CONFIGURATION,
                    "无法还原密钥对 (algorithm=" + algorithm + ")", e);
        }
    }

    String secretBase64() {
        return secret == null ? null : Base64.getEncoder().encodeToString(secret);
    }

    String privateBase64() {
        return privateKey == null ? null : Base64.getEncoder().encodeToString(privateKey);
    }

    String publicBase64() {
        return publicKey == null ? null : Base64.getEncoder().encodeToString(publicKey);
    }
}
