package cn.byteforce.seedj.infra.local.transit;

import cn.byteforce.seedj.transit.KeyType;
import cn.byteforce.seedj.transit.SignatureAlgorithm;
import cn.byteforce.seedj.transit.TransitErrorCode;
import cn.byteforce.seedj.transit.TransitException;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/** BouncyCastle 密钥材料构造与算法映射：无状态，供 provider 与内部实现共用。 */
final class BcTransitKeys {

    static KeyMaterial requireVersion(StoredKey entry, int version) {
        KeyMaterial material = entry.versions().get(version);
        if (material == null) {
            throw new TransitException(TransitErrorCode.VERSION_NOT_FOUND, "Version not found: " + version, null);
        }
        return material;
    }

    static KeyMaterial generateKeyMaterial(KeyType type) {
        try {
            return switch (type) {
                case AES128_GCM96 -> KeyMaterial.of(generateAes(128));
                case AES256_GCM96, CHACHA20_POLY1305 -> KeyMaterial.of(generateAes(256));
                case AES128_CMAC -> KeyMaterial.of(generateAes(128));
                case AES256_CMAC -> KeyMaterial.of(generateAes(256));
                case RSA_2048 -> KeyMaterial.of(generateRsa(2048), "RSA");
                case RSA_3072 -> KeyMaterial.of(generateRsa(3072), "RSA");
                case RSA_4096 -> KeyMaterial.of(generateRsa(4096), "RSA");
                case ECDSA_P256 -> KeyMaterial.of(generateEc("secp256r1"), "EC");
                case ECDSA_P384 -> KeyMaterial.of(generateEc("secp384r1"), "EC");
                case ECDSA_P521 -> KeyMaterial.of(generateEc("secp521r1"), "EC");
                case ED25519 -> KeyMaterial.of(generateEd25519(), "Ed25519");
                case HMAC -> KeyMaterial.of(generateHmac());
            };
        } catch (TransitException e) {
            throw e;
        } catch (Exception e) {
            throw new TransitException(TransitErrorCode.INVALID_CONFIGURATION,
                    "生成密钥材料失败: " + type, e);
        }
    }

    static SecretKey secretKey(StoredKey entry, int version, String context) throws Exception {
        KeyMaterial material = requireVersion(entry, version);
        if (!material.isSymmetric()) {
            throw new TransitException(TransitErrorCode.TYPE_MISMATCH, "Key is not symmetric", null);
        }
        SecretKey baseKey = material.toSecretKey();

        if (!entry.derived()) {
            if (context != null) {
                throw new TransitException(TransitErrorCode.INVALID_CONTEXT,
                        "Context provided but key is not derived", null);
            }
            return baseKey;
        }
        if (context == null) {
            throw new TransitException(TransitErrorCode.INVALID_CONTEXT,
                    "Key is derived, context is required", null);
        }

        Mac mac = Mac.getInstance("HmacSHA256", "BC");
        mac.init(baseKey);
        byte[] derived = mac.doFinal(Base64.getDecoder().decode(context));
        int keyLen = entry.type().name().contains("128") ? 16 : 32;
        return new SecretKeySpec(Arrays.copyOf(derived, keyLen), "AES");
    }

    static String transformation(KeyType type) {
        return type == KeyType.CHACHA20_POLY1305 ? "ChaCha20-Poly1305" : "AES/GCM/NoPadding";
    }

    static SignatureAlgorithm inferSignatureAlgorithm(KeyType type) {
        if (type == KeyType.ED25519) {
            return SignatureAlgorithm.ED25519;
        }
        if (type.name().startsWith("RSA")) {
            return SignatureAlgorithm.SHA256_WITH_RSA;
        }
        if (type.name().startsWith("ECDSA")) {
            return SignatureAlgorithm.SHA256_WITH_ECDSA;
        }
        throw new TransitException(TransitErrorCode.UNSUPPORTED_ALGORITHM,
                "Cannot infer signature algorithm for: " + type, null);
    }

    static String mapSignatureAlgorithm(SignatureAlgorithm alg) {
        return switch (alg) {
            case SHA256_WITH_RSA -> "SHA256withRSA";
            case SHA384_WITH_RSA -> "SHA384withRSA";
            case SHA512_WITH_RSA -> "SHA512withRSA";
            case SHA256_WITH_ECDSA -> "SHA256withECDSA";
            case SHA384_WITH_ECDSA -> "SHA384withECDSA";
            case SHA512_WITH_ECDSA -> "SHA512withECDSA";
            case ED25519 -> "Ed25519";
        };
    }

    static SecretKey generateAes(int size) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(size);
        return kg.generateKey();
    }

    static SecretKey generateHmac() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
        kg.init(256);
        return kg.generateKey();
    }

    static KeyPair generateRsa(int size) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(size);
        return kpg.generateKeyPair();
    }

    static KeyPair generateEc(String curve) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec(curve));
        return kpg.generateKeyPair();
    }

    static KeyPair generateEd25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair();
    }
}
