package cn.byteforce.seedj.starter.ai.token;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

/**
 * Ed25519 密钥材料的唯一处理点：解析配置、推导公钥、JCA 签名与验签。
 *
 * <p>为什么不用 Nimbus 自带的 {@code Ed25519Signer}/{@code Ed25519Verifier}：它们在 Nimbus 10 里依赖 Tink，
 * 而 Tink 是 optional 依赖、未进入本项目依赖树，运行时直接 {@code NoClassDefFoundError}。这里改用 JDK JCA
 * 做 Ed25519 运算，并由 BouncyCastle 从 seed 推导公钥——JDK 只能从 seed 造私钥，没有「私钥 → 公钥」的 API。
 * 协议形态仍由 Nimbus 保证（见 {@link JcaEd25519Signer}），将来换 seedj PKI 或换实现不改 wire 格式。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class Ed25519Keys {

    /** Ed25519 seed 与裸公钥的字节长度。 */
    static final int KEY_LENGTH = 32;

    private static final String JCA_ALGORITHM = "Ed25519";

    private Ed25519Keys() {}

    /**
     * 解析配置里的私钥：base64url 的 32 字节 seed，或 base64/base64url 的 PKCS#8 DER。
     *
     * @param value 配置值
     * @return JCA 形式的 Ed25519 私钥
     * @throws NullPointerException {@code value} 为 {@code null}
     * @throws IllegalArgumentException 不是合法 base64，或不是上述两种密钥形态之一
     */
    static EdECPrivateKey parsePrivateKey(String value) {
        Objects.requireNonNull(value, "value");
        byte[] decoded = decodeBase64(value);
        if (decoded.length == KEY_LENGTH) {
            return privateKey(decoded);
        }
        try {
            PrivateKey parsed =
                    KeyFactory.getInstance(JCA_ALGORITHM)
                            .generatePrivate(new PKCS8EncodedKeySpec(decoded));
            return (EdECPrivateKey) parsed;
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalArgumentException(
                    "seedj.ai.decision.signing-key 必须是 base64url 的 32 字节 Ed25519 seed，"
                            + "或 base64/base64url 的 PKCS#8 DER",
                    e);
        }
    }

    /**
     * 由私钥推导 32 字节裸公钥。
     *
     * @param privateKey JCA Ed25519 私钥
     * @return {@value #KEY_LENGTH} 字节裸公钥（Ed25519 编码，小端 + 符号位）
     * @throws IllegalArgumentException 私钥不暴露 seed 字节
     */
    static byte[] rawPublicKey(EdECPrivateKey privateKey) {
        byte[] seed =
                Objects.requireNonNull(privateKey, "privateKey")
                        .getBytes()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "ed25519 private key does not expose its seed"));
        return new Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().getEncoded();
    }

    /**
     * 由 32 字节裸公钥构造 JCA 公钥。
     *
     * @param raw {@value #KEY_LENGTH} 字节裸公钥（Ed25519 编码，小端 + 符号位）
     * @return JCA 公钥
     * @throws IllegalArgumentException 长度不是 {@value #KEY_LENGTH}
     */
    static PublicKey publicKey(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "ed25519 public key must be " + KEY_LENGTH + " bytes, was " + raw.length);
        }
        boolean xOdd = (raw[KEY_LENGTH - 1] & 0x80) != 0;
        byte[] littleEndian = raw.clone();
        littleEndian[KEY_LENGTH - 1] &= (byte) 0x7f;
        byte[] bigEndian = new byte[KEY_LENGTH];
        for (int i = 0; i < KEY_LENGTH; i++) {
            bigEndian[i] = littleEndian[KEY_LENGTH - 1 - i];
        }
        try {
            return KeyFactory.getInstance(JCA_ALGORITHM)
                    .generatePublic(
                            new EdECPublicKeySpec(
                                    NamedParameterSpec.ED25519,
                                    new EdECPoint(xOdd, new BigInteger(1, bigEndian))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("jdk cannot build an ed25519 public key", e);
        }
    }

    /**
     * 由 32 字节 seed 构造 JCA 私钥。
     *
     * @param seed {@value #KEY_LENGTH} 字节 seed
     * @return JCA 私钥
     * @throws IllegalArgumentException 长度不是 {@value #KEY_LENGTH}
     */
    static EdECPrivateKey privateKey(byte[] seed) {
        Objects.requireNonNull(seed, "seed");
        if (seed.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "ed25519 seed must be " + KEY_LENGTH + " bytes, was " + seed.length);
        }
        try {
            return (EdECPrivateKey)
                    KeyFactory.getInstance(JCA_ALGORITHM)
                            .generatePrivate(
                                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("jdk cannot build an ed25519 private key", e);
        }
    }

    static byte[] sign(PrivateKey privateKey, byte[] input) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(JCA_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(input);
        return signature.sign();
    }

    static boolean verify(PublicKey publicKey, byte[] input, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance(JCA_ALGORITHM);
        verifier.initVerify(publicKey);
        verifier.update(input);
        return verifier.verify(signature);
    }

    private static byte[] decodeBase64(String value) {
        String compact = value.replaceAll("\\s+", "");
        try {
            return Base64.getUrlDecoder().decode(compact);
        } catch (IllegalArgumentException urlDecodingFailed) {
            try {
                return Base64.getDecoder().decode(compact);
            } catch (IllegalArgumentException base64DecodingFailed) {
                throw new IllegalArgumentException(
                        "seedj.ai.decision.signing-key 不是合法的 base64/base64url 文本",
                        base64DecodingFailed);
            }
        }
    }
}
