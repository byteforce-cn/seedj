package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.provider.pki.RevocationReason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 基于 {@code Properties} 文件的状态存储，多服务共享同一文件即可保证状态一致。
 *
 * <p>文件格式：
 *
 * <pre>
 * issued.&lt;serial-hex&gt;=&lt;notAfterEpochMilli&gt;
 * issuedcert.&lt;serial-hex&gt;=&lt;base64 DER&gt;
 * revoked.&lt;serial-hex&gt;=&lt;revokedAtEpochMilli&gt;|&lt;REVOCATION_REASON&gt;
 * crl.number=&lt;monotonic counter&gt;
 * keypair.&lt;alias&gt;.private=&lt;base64 PKCS#8&gt;
 * keypair.&lt;alias&gt;.public=&lt;base64 X.509&gt;
 * identity.&lt;alias&gt;.cert=&lt;base64 DER&gt;
 * </pre>
 *
 * <p>并发控制：
 *
 * <ul>
 *   <li>跨进程：{@link FileLock} 独占锁；
 *   <li>同 JVM 多实例：{@link ReentrantLock}（{@code FileChannel.lock()} 对同 JVM 内多个 channel 会抛 {@code
 *       OverlappingFileLockException}）。
 * </ul>
 *
 * <p>原子性：所有写操作在锁内完成"读—改—truncate—写—force"，进程崩溃最多丢失 最后一次未落盘的写入，不会产生半截文件。
 */
final class PkiStateFile {

    /** 同 JVM 内多实例共享的本地锁，按规范化绝对路径分桶 */
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS =
            new ConcurrentHashMap<>();

    private static final String ISSUED_PREFIX = "issued.";
    private static final String ISSUED_CERT_PREFIX = "issuedcert.";
    private static final String REVOKED_PREFIX = "revoked.";
    private static final String CRL_NUMBER = "crl.number";
    private static final String KEYPAIR_PREFIX = "keypair.";
    private static final String KEYPAIR_PRIVATE_SUFFIX = ".private";
    private static final String KEYPAIR_PUBLIC_SUFFIX = ".public";
    private static final String IDENTITY_PREFIX = "identity.";
    private static final String IDENTITY_CERT_SUFFIX = ".cert";

    private static final String FILE_HEADER = "seedj PKI state";

    /** 一条 CRL 条目。 */
    record RevokedEntry(BigInteger serial, Instant revokedAt, RevocationReason reason) {}

    private final Path file;

    PkiStateFile(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    // ==================== 证书状态 ====================

    void recordIssued(X509Certificate certificate) {
        String serial = serialOf(certificate);
        String issuedKey = ISSUED_PREFIX + serial;
        String certKey = ISSUED_CERT_PREFIX + serial;
        long notAfter = certificate.getNotAfter().getTime();
        String encoded = encode(certificate);
        // 只增不减：同一 serial 重签（过期续期）时顺延 notAfter；普通签发 serial 不同，行为不变。
        update(
                props -> {
                    long previous =
                            props.containsKey(issuedKey)
                                    ? Long.parseLong(props.getProperty(issuedKey))
                                    : Long.MIN_VALUE;
                    props.setProperty(issuedKey, String.valueOf(Math.max(previous, notAfter)));
                    props.setProperty(certKey, encoded);
                });
    }

    Optional<X509Certificate> certificate(BigInteger serialNumber) {
        String encoded = read().getProperty(ISSUED_CERT_PREFIX + hex(serialNumber));
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return Optional.of(
                    (X509Certificate)
                            factory.generateCertificate(
                                    new ByteArrayInputStream(Base64.getDecoder().decode(encoded))));
        } catch (Exception e) {
            throw new PkiException(
                    PkiErrorCode.STATE_UNAVAILABLE,
                    "解码已签发证书失败: " + hex(serialNumber),
                    e);
        }
    }

    boolean revoked(BigInteger serialNumber) {
        return read().getProperty(REVOKED_PREFIX + hex(serialNumber)) != null;
    }

    void recordRevoked(BigInteger serialNumber, RevocationReason reason) {
        String hex = hex(serialNumber);
        update(
                props -> {
                    if (!props.containsKey(ISSUED_PREFIX + hex)) {
                        throw new PkiException(
                                PkiErrorCode.NOT_FOUND, "未签发的序列号: " + hex);
                    }
                    // 重复吊销幂等：保留首次的 reason 与时间。
                    if (props.containsKey(REVOKED_PREFIX + hex)) {
                        return;
                    }
                    props.setProperty(
                            REVOKED_PREFIX + hex,
                            Instant.now().toEpochMilli() + "|" + reason.name());
                });
    }

    List<RevokedEntry> revokedEntries() {
        Properties props = read();
        List<RevokedEntry> entries = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith(REVOKED_PREFIX)) {
                continue;
            }
            BigInteger serial = new BigInteger(key.substring(REVOKED_PREFIX.length()), 16);
            String value = props.getProperty(key);
            String[] parts = value.split("\\|", 2);
            try {
                if (parts.length == 2) {
                    entries.add(
                            new RevokedEntry(
                                    serial,
                                    Instant.ofEpochMilli(Long.parseLong(parts[0])),
                                    RevocationReason.valueOf(parts[1])));
                } else {
                    // 旧格式只有 reason；用证书 notBefore 兜底，实在没有就用 epoch。
                    entries.add(
                            new RevokedEntry(
                                    serial,
                                    certificate(serial)
                                            .map(cert -> cert.getNotBefore().toInstant())
                                            .orElse(Instant.EPOCH),
                                    RevocationReason.valueOf(parts[0])));
                }
            } catch (RuntimeException e) {
                throw new PkiException(
                        PkiErrorCode.STATE_UNAVAILABLE, "解析吊销记录失败: " + key, e);
            }
        }
        return List.copyOf(entries);
    }

    long crlNumber() {
        String value = read().getProperty(CRL_NUMBER);
        return value == null ? 1L : Long.parseLong(value);
    }

    long nextCrlNumber() {
        AtomicReference<Long> next = new AtomicReference<>();
        update(
                props -> {
                    long value =
                            props.containsKey(CRL_NUMBER)
                                    ? Long.parseLong(props.getProperty(CRL_NUMBER)) + 1
                                    : 2;
                    props.setProperty(CRL_NUMBER, String.valueOf(value));
                    next.set(value);
                });
        return next.get();
    }

    /**
     * 删除 {@code cutoff} 之前到期的签发记录；吊销记录与已吊销证书永不删除。
     *
     * @return 删除的证书记录数
     */
    int tidy(Instant cutoff) {
        AtomicReference<Integer> removed = new AtomicReference<>(0);
        update(
                props -> {
                    int count = 0;
                    for (String key : new ArrayList<>(props.stringPropertyNames())) {
                        if (!key.startsWith(ISSUED_PREFIX)) {
                            continue;
                        }
                        String hex = key.substring(ISSUED_PREFIX.length());
                        if (props.containsKey(REVOKED_PREFIX + hex)) {
                            continue;
                        }
                        long notAfter = Long.parseLong(props.getProperty(key));
                        if (notAfter >= cutoff.toEpochMilli()) {
                            continue;
                        }
                        props.remove(key);
                        props.remove(ISSUED_CERT_PREFIX + hex);
                        count++;
                    }
                    removed.set(count);
                });
        return removed.get();
    }

    void clear() {
        update(Properties::clear);
    }

    // ==================== 密钥对 ====================

    KeyPair getOrCreateKeyPair(String alias, int keySize) {
        AtomicReference<KeyPair> result = new AtomicReference<>();
        update(
                props -> {
                    String privateProp = KEYPAIR_PREFIX + alias + KEYPAIR_PRIVATE_SUFFIX;
                    String publicProp = KEYPAIR_PREFIX + alias + KEYPAIR_PUBLIC_SUFFIX;
                    String privateB64 = props.getProperty(privateProp);
                    String publicB64 = props.getProperty(publicProp);

                    if (privateB64 != null && publicB64 != null) {
                        result.set(decodeKeyPair(privateB64, publicB64));
                        return;
                    }

                    // 首次生成：在 FileLock 临界区内写入，后到的实例会读到先写入的值
                    KeyPair kp = generateKeyPair(keySize);
                    props.setProperty(
                            privateProp,
                            Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
                    props.setProperty(
                            publicProp,
                            Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
                    result.set(kp);
                });
        return result.get();
    }

    // ==================== 签名身份 ====================

    KeyPair rotateKeyPair(String alias, int keySize) {
        AtomicReference<KeyPair> result = new AtomicReference<>();
        update(
                props -> {
                    KeyPair kp = generateKeyPair(keySize);
                    props.setProperty(
                            KEYPAIR_PREFIX + alias + KEYPAIR_PRIVATE_SUFFIX,
                            Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
                    props.setProperty(
                            KEYPAIR_PREFIX + alias + KEYPAIR_PUBLIC_SUFFIX,
                            Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
                    result.set(kp);
                });
        return result.get();
    }

    Optional<X509Certificate> identityCertificate(String alias) {
        String encoded = read().getProperty(identityCertKey(alias));
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return Optional.of(
                    (X509Certificate)
                            factory.generateCertificate(
                                    new ByteArrayInputStream(Base64.getDecoder().decode(encoded))));
        } catch (Exception e) {
            throw new PkiException("解码签名身份证书失败: " + alias, e);
        }
    }

    void putIdentityCertificate(String alias, X509Certificate cert) {
        String key = identityCertKey(alias);
        String encoded = encode(cert);
        update(props -> props.setProperty(key, encoded));
    }

    private static String identityCertKey(String alias) {
        return IDENTITY_PREFIX + alias + IDENTITY_CERT_SUFFIX;
    }

    // ==================== 文件读写 ====================

    private Properties read() {
        if (!Files.exists(file)) {
            return new Properties();
        }
        ReentrantLock localLock = localLock();
        localLock.lock();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return readChannel(channel);
        } catch (IOException e) {
            throw new PkiException(
                    PkiErrorCode.STATE_UNAVAILABLE, "读取 PKI 状态文件失败: " + file, e);
        } finally {
            localLock.unlock();
        }
    }

    private void update(Consumer<Properties> mutator) {
        prepareParentDirectory();

        ReentrantLock localLock = localLock();
        localLock.lock();
        try (FileChannel channel =
                        FileChannel.open(
                                file,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {

            Properties props = readChannel(channel);
            mutator.accept(props);
            writeChannel(channel, props);
        } catch (IOException e) {
            throw new PkiException(
                    PkiErrorCode.STATE_UNAVAILABLE, "更新 PKI 状态文件失败: " + file, e);
        } finally {
            localLock.unlock();
        }
    }

    private void prepareParentDirectory() {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new PkiException(
                    PkiErrorCode.STATE_UNAVAILABLE, "创建 PKI 状态目录失败: " + parent, e);
        }
    }

    private ReentrantLock localLock() {
        return LOCAL_LOCKS.computeIfAbsent(file, k -> new ReentrantLock());
    }

    private static Properties readChannel(FileChannel channel) throws IOException {
        Properties props = new Properties();
        long size = channel.size();
        if (size == 0) {
            return props;
        }
        byte[] data = new byte[(int) size];
        ByteBuffer buf = ByteBuffer.wrap(data);
        channel.position(0);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                break;
            }
        }
        props.load(new ByteArrayInputStream(data));
        return props;
    }

    private static void writeChannel(FileChannel channel, Properties props) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        props.store(bos, FILE_HEADER);
        byte[] out = bos.toByteArray();

        channel.truncate(0);
        channel.position(0);
        ByteBuffer buf = ByteBuffer.wrap(out);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        channel.force(true);
    }

    // ==================== 密钥对工具 ====================

    private static String serialOf(X509Certificate certificate) {
        return hex(certificate.getSerialNumber());
    }

    private static String hex(BigInteger serial) {
        return serial.toString(16);
    }

    private static String encode(X509Certificate certificate) {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (Exception e) {
            throw new PkiException("编码证书失败", e);
        }
    }

    private static KeyPair generateKeyPair(int keySize) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(keySize);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new PkiException("生成 RSA 密钥对失败 (keySize=" + keySize + ")", e);
        }
    }

    private static KeyPair decodeKeyPair(String privateB64, String publicB64) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv =
                    kf.generatePrivate(
                            new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
            PublicKey pub =
                    kf.generatePublic(
                            new X509EncodedKeySpec(Base64.getDecoder().decode(publicB64)));
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            throw new PkiException("解码 PKI 密钥对失败", e);
        }
    }
}
