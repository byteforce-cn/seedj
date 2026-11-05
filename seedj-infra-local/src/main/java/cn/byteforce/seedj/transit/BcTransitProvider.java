package cn.byteforce.seedj.transit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 基于 JCE + BouncyCastle 的实现。
 *
 * <p>密钥材料同时使用内存与磁盘：状态由 {@link TransitState} 提供，
 * 默认持久化到 {@code seedj.transit.state-file}，同机多服务共享同一文件即共用密钥。
 *
 * <p><b>注意：</b>本实现的算法选择以"本地开发/测试可用"为目标，
 * 生产级 KMS（Vault / KMS / HSM）应另写一个 provider，而不是修改本类。
 */
public class BcTransitProvider implements TransitProvider {

    private final TransitState state;
    private volatile boolean closed = false;

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    /**
     * 纯内存 provider（单元测试用；进程重启丢失密钥）。
     */
    public BcTransitProvider() {
        this(new InMemoryTransitState());
    }

    /**
     * 磁盘共享状态：多服务指向同一文件即共用密钥与版本历史。
     */
    public static BcTransitProvider fileBacked(java.nio.file.Path stateFile) {
        return new BcTransitProvider(new FileTransitState(new TransitStateFile(stateFile)));
    }

    /**
     * 指定状态后端（实现内部 seam，仅同包可见）。
     */
    BcTransitProvider(TransitState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    // ==================== SPI 主入口 ====================

    @Override
    public TransitCrypto crypto() {
        return new BcCrypto();
    }

    @Override
    public TransitSigner signer() {
        return new BcSigner();
    }

    @Override
    public TransitMac mac() {
        return new BcMac();
    }

    @Override
    public TransitKeyAdmin keyAdmin() {
        return new BcKeyAdmin();
    }

    @Override
    public boolean supports(KeyType keyType, TransitOperation operation) {
        if (keyType == null || operation == null) {
            return false;
        }
        return switch (operation) {
            case ENCRYPT, DECRYPT, REWRAP, GENERATE_DATA_KEY ->
                    keyType.name().startsWith("AES") || keyType == KeyType.CHACHA20_POLY1305;
            case SIGN, VERIFY ->
                    keyType.name().startsWith("RSA") || keyType.name().startsWith("ECDSA")
                            || keyType == KeyType.ED25519;
            case HMAC, VERIFY_HMAC ->
                    keyType == KeyType.HMAC || keyType.name().endsWith("_CMAC");
            default -> true;
        };
    }

    @Override
    public void close() {
        closed = true;
    }

    // ==================== 内部工具 ====================

    private void ensureOpen() {
        if (closed) {
            throw new TransitException(TransitErrorCode.PROVIDER_CLOSED, "Provider is closed", null);
        }
    }

    private <T> T execute(TransitErrorCode defaultErr, java.util.concurrent.Callable<T> task) {
        ensureOpen();
        try {
            return task.call();
        } catch (TransitException e) {
            throw e;
        } catch (Exception e) {
            throw new TransitException(defaultErr, "Cryptographic operation failed: " + e.getMessage(), e);
        }
    }

    private StoredKey requireKey(Map<String, StoredKey> snapshot, String keyName) {
        StoredKey entry = snapshot.get(keyName);
        if (entry == null) {
            throw new TransitException(TransitErrorCode.KEY_NOT_FOUND, "Key not found: " + keyName, null);
        }
        return entry;
    }

    private static KeyMaterial requireVersion(StoredKey entry, int version) {
        KeyMaterial material = entry.versions().get(version);
        if (material == null) {
            throw new TransitException(TransitErrorCode.VERSION_NOT_FOUND, "Version not found: " + version, null);
        }
        return material;
    }

    // ==================== 密钥管理 ====================

    private final class BcKeyAdmin implements TransitKeyAdmin {

        @Override
        public KeyInfo createKey(CreateKeyRequest request) {
            ensureOpen();
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> state.update(keys -> {
                boolean derived = Boolean.TRUE.equals(request.getDerived());
                boolean exportable = Boolean.TRUE.equals(request.getExportable());
                boolean convergent = Boolean.TRUE.equals(request.getConvergentEncryption());

                StoredKey existing = keys.get(request.getKeyName());
                if (existing != null) {
                    if (existing.type() != request.getType()
                            || existing.derived() != derived
                            || existing.exportable() != exportable
                            || existing.convergentEncryption() != convergent) {
                        throw new TransitException(TransitErrorCode.KEY_ALREADY_EXISTS,
                                "Key exists with different configuration", null);
                    }
                    return existing.toKeyInfo(request.getKeyName());
                }

                StoredKey created = StoredKey.create(request.getType(), derived, exportable,
                        convergent, false, 1, generateKeyMaterial(request.getType()));
                keys.put(request.getKeyName(), created);
                return created.toKeyInfo(request.getKeyName());
            }));
        }

        @Override
        public KeyInfo readKey(String keyName) {
            ensureOpen();
            return execute(TransitErrorCode.KEY_NOT_FOUND,
                    () -> requireKey(state.snapshot(), keyName).toKeyInfo(keyName));
        }

        @Override
        public void rotateKey(String keyName) {
            ensureOpen();
            execute(TransitErrorCode.KEY_NOT_FOUND, () -> state.update(keys -> {
                StoredKey entry = requireKey(keys, keyName);
                keys.put(keyName, entry.withNewVersion(generateKeyMaterial(entry.type())));
                return null;
            }));
        }

        @Override
        public void configureKey(String keyName, KeyConfigRequest request) {
            ensureOpen();
            execute(TransitErrorCode.INVALID_CONFIGURATION, () -> state.update(keys -> {
                StoredKey entry = requireKey(keys, keyName);
                keys.put(keyName, entry.withVersions(
                        request.getMinDecryptionVersion() == null ? 0 : request.getMinDecryptionVersion(),
                        request.getMinEncryptionVersion() == null ? 0 : request.getMinEncryptionVersion()));
                return null;
            }));
        }

        @Override
        public void deleteKey(String keyName) {
            ensureOpen();
            execute(TransitErrorCode.KEY_NOT_FOUND, () -> state.update(keys -> {
                keys.remove(keyName);
                return null;
            }));
        }

        @Override
        public ExportedKey exportKey(String keyName, KeyType type, Integer version) {
            ensureOpen();
            return execute(TransitErrorCode.NOT_EXPORTABLE, () -> {
                StoredKey entry = requireKey(state.snapshot(), keyName);
                if (!entry.exportable()) {
                    throw new TransitException(TransitErrorCode.NOT_EXPORTABLE, "Key is not exportable", null);
                }
                if (entry.type() != type) {
                    throw new TransitException(TransitErrorCode.TYPE_MISMATCH, "Type mismatch", null);
                }
                Map<Integer, String> exported = new LinkedHashMap<>();
                if (version != null) {
                    exported.put(version, encodeMaterial(requireVersion(entry, version)));
                } else {
                    entry.versions().forEach((v, material) -> exported.put(v, encodeMaterial(material)));
                }
                return new ExportedKey(type, !entry.versions().values().iterator().next().isSymmetric(), exported);
            });
        }

        private String encodeMaterial(KeyMaterial material) {
            return material.isSymmetric()
                    ? material.secretBase64()
                    : material.privateBase64() + ":" + material.publicBase64();
        }
    }

    // ==================== 加解密 ====================

    private final class BcCrypto implements TransitCrypto {

        @Override
        public Ciphertext encrypt(EncryptRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                if (!supports(entry.type(), TransitOperation.ENCRYPT)) {
                    throw new TransitException(TransitErrorCode.TYPE_MISMATCH, "Type mismatch", null);
                }

                int targetVersion = request.getKeyVersion() != null
                        ? request.getKeyVersion()
                        : entry.latestVersion();
                SecretKey key = secretKey(entry, targetVersion, request.getContext());

                byte[] iv = new byte[12];
                SecureRandom.getInstanceStrong().nextBytes(iv);

                Cipher cipher = Cipher.getInstance(transformation(entry.type()), "BC");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));

                byte[] cipherText = cipher.doFinal(request.getPlaintext().getBytes(StandardCharsets.UTF_8));
                byte[] combined = new byte[iv.length + cipherText.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

                return new Ciphertext("v" + targetVersion + ":"
                        + Base64.getEncoder().encodeToString(combined), targetVersion, null);
            });
        }

        @Override
        public String decrypt(DecryptRequest request) {
            return execute(TransitErrorCode.DECRYPTION_FAILED, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                String[] parts = request.getCiphertext().split(":", 2);
                if (parts.length != 2 || !parts[0].startsWith("v")) {
                    throw new TransitException(TransitErrorCode.INVALID_CIPHERTEXT, "Invalid format", null);
                }

                int version = Integer.parseInt(parts[0].substring(1));
                if (version < entry.minDecryptionVersion()) {
                    throw new TransitException(TransitErrorCode.VERSION_NOT_FOUND, "Version too old", null);
                }

                byte[] combined = Base64.getDecoder().decode(parts[1]);
                byte[] iv = Arrays.copyOfRange(combined, 0, 12);
                byte[] cipherText = Arrays.copyOfRange(combined, 12, combined.length);

                Cipher cipher = Cipher.getInstance(transformation(entry.type()), "BC");
                cipher.init(Cipher.DECRYPT_MODE, secretKey(entry, version, request.getContext()),
                        new GCMParameterSpec(128, iv));

                return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
            });
        }

        @Override
        public Ciphertext rewrap(RewrapRequest request) {
            String plain = decrypt(new DecryptRequest(request.getKeyName(), request.getCiphertext(),
                    request.getContext()));
            return encrypt(new EncryptRequest(request.getKeyName(), plain, request.getContext(), null));
        }

        @Override
        public List<BatchResult<Ciphertext>> encryptBatch(BatchEncryptRequest request) {
            return request.getItems().stream().map(item -> {
                try {
                    EncryptRequest req = new EncryptRequest(request.getKeyName(), item.getInput(),
                            item.getContext() != null ? item.getContext() : request.getGlobalContext(),
                            item.getKeyVersion() != null ? item.getKeyVersion() : request.getGlobalKeyVersion());
                    return new BatchResult<>(item.getReference(), encrypt(req), null, null);
                } catch (TransitException e) {
                    return new BatchResult<Ciphertext>(item.getReference(), null, e.getErrorCode(), e.getMessage());
                }
            }).collect(Collectors.toList());
        }

        @Override
        public List<BatchResult<String>> decryptBatch(BatchDecryptRequest request) {
            return request.getItems().stream().map(item -> {
                try {
                    DecryptRequest req = new DecryptRequest(request.getKeyName(), item.getInput(),
                            item.getContext() != null ? item.getContext() : request.getGlobalContext());
                    return new BatchResult<>(item.getReference(), decrypt(req), null, null);
                } catch (TransitException e) {
                    return new BatchResult<String>(item.getReference(), null, e.getErrorCode(), e.getMessage());
                }
            }).collect(Collectors.toList());
        }

        @Override
        public List<BatchResult<Ciphertext>> rewrapBatch(BatchRewrapRequest request) {
            return request.getItems().stream().map(item -> {
                try {
                    RewrapRequest req = new RewrapRequest(request.getKeyName(), item.getInput(),
                            item.getContext() != null ? item.getContext() : request.getGlobalContext());
                    return new BatchResult<>(item.getReference(), rewrap(req), null, null);
                } catch (TransitException e) {
                    return new BatchResult<Ciphertext>(item.getReference(), null, e.getErrorCode(), e.getMessage());
                }
            }).collect(Collectors.toList());
        }

        @Override
        public DataKey generateDataKey(DataKeyRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256);
                SecretKey dataKey = kg.generateKey();

                String plainB64 = Base64.getEncoder().encodeToString(dataKey.getEncoded());
                Ciphertext wrappedKey = encrypt(new EncryptRequest(request.getKeyName(), plainB64,
                        request.getContext(), request.getKeyVersion()));

                return new DataKey(request.isWithPlaintext() ? dataKey.getEncoded() : null, wrappedKey);
            });
        }
    }

    // ==================== 签名与 MAC ====================

    private final class BcSigner implements TransitSigner {

        @Override
        public Signature sign(SignRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                int version = request.getKeyVersion() != null
                        ? request.getKeyVersion() : entry.latestVersion();
                KeyPair kp = requireVersion(entry, version).toKeyPair();

                SignatureAlgorithm alg = request.getAlgorithm() != null
                        ? request.getAlgorithm() : inferSignatureAlgorithm(entry.type());

                java.security.Signature signer = java.security.Signature.getInstance(
                        mapSignatureAlgorithm(alg), "BC");
                signer.initSign(kp.getPrivate());
                signer.update(request.getData().getBytes(StandardCharsets.UTF_8));

                return new Signature(Base64.getEncoder().encodeToString(signer.sign()), alg,
                        request.getKeyVersion());
            });
        }

        @Override
        public boolean verify(VerifyRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                SignatureAlgorithm alg = request.getAlgorithm() != null
                        ? request.getAlgorithm() : inferSignatureAlgorithm(entry.type());

                int startVer = request.getKeyVersion() != null
                        ? request.getKeyVersion() : entry.latestVersion();
                byte[] signature = Base64.getDecoder().decode(request.getSignature());

                for (int v = startVer; v >= entry.minDecryptionVersion(); v--) {
                    KeyMaterial material = entry.versions().get(v);
                    if (material == null || material.isSymmetric()) {
                        continue;
                    }
                    try {
                        java.security.Signature verifier = java.security.Signature.getInstance(
                                mapSignatureAlgorithm(alg), "BC");
                        verifier.initVerify(material.toKeyPair().getPublic());
                        verifier.update(request.getData().getBytes(StandardCharsets.UTF_8));
                        if (verifier.verify(signature)) {
                            return true;
                        }
                    } catch (Exception ignored) {
                        // 尝试下一个版本
                    }
                }
                return false;
            });
        }
    }

    private final class BcMac implements TransitMac {

        @Override
        public MacResult hmac(HmacRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                int version = request.getKeyVersion() != null
                        ? request.getKeyVersion() : entry.latestVersion();
                SecretKey key = requireVersion(entry, version).toSecretKey();

                HmacAlgorithm alg = request.getAlgorithm() != null
                        ? request.getAlgorithm() : HmacAlgorithm.SHA256;
                Mac mac = Mac.getInstance("Hmac" + alg.name(), "BC");
                mac.init(key);
                byte[] hmacBytes = mac.doFinal(request.getData().getBytes(StandardCharsets.UTF_8));

                return new MacResult(Base64.getEncoder().encodeToString(hmacBytes), alg,
                        request.getKeyVersion());
            });
        }

        @Override
        public boolean verifyHmac(VerifyHmacRequest request) {
            return execute(TransitErrorCode.INVALID_CONFIGURATION, () -> {
                StoredKey entry = requireKey(state.snapshot(), request.getKeyName());
                HmacAlgorithm alg = request.getAlgorithm() != null
                        ? request.getAlgorithm() : HmacAlgorithm.SHA256;

                int startVer = request.getKeyVersion() != null
                        ? request.getKeyVersion() : entry.latestVersion();
                byte[] provided = Base64.getDecoder().decode(request.getHmac());

                for (int v = startVer; v >= entry.minDecryptionVersion(); v--) {
                    KeyMaterial material = entry.versions().get(v);
                    if (material == null || !material.isSymmetric()) {
                        continue;
                    }
                    Mac mac = Mac.getInstance("Hmac" + alg.name(), "BC");
                    mac.init(material.toSecretKey());
                    byte[] computed = mac.doFinal(request.getData().getBytes(StandardCharsets.UTF_8));
                    if (MessageDigest.isEqual(provided, computed)) {
                        return true;
                    }
                }
                return false;
            });
        }
    }

    // ==================== 密钥材料 ====================

    private KeyMaterial generateKeyMaterial(KeyType type) {
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

    private SecretKey secretKey(StoredKey entry, int version, String context) throws Exception {
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

    private static String transformation(KeyType type) {
        return type == KeyType.CHACHA20_POLY1305 ? "ChaCha20-Poly1305" : "AES/GCM/NoPadding";
    }

    private static SignatureAlgorithm inferSignatureAlgorithm(KeyType type) {
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

    private static String mapSignatureAlgorithm(SignatureAlgorithm alg) {
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

    private static SecretKey generateAes(int size) throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(size);
        return kg.generateKey();
    }

    private static SecretKey generateHmac() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
        kg.init(256);
        return kg.generateKey();
    }

    private static KeyPair generateRsa(int size) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(size);
        return kpg.generateKeyPair();
    }

    private static KeyPair generateEc(String curve) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
        kpg.initialize(new ECGenParameterSpec(curve));
        return kpg.generateKeyPair();
    }

    private static KeyPair generateEd25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519", "BC").generateKeyPair();
    }
}
