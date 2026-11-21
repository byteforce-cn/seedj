package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.observability.HealthStatus;
import cn.byteforce.seedj.provider.pki.CertificateRequest;
import cn.byteforce.seedj.provider.pki.CertificateRevokedException;
import cn.byteforce.seedj.provider.pki.CertificateUsage;
import cn.byteforce.seedj.provider.pki.ClientAuth;
import cn.byteforce.seedj.provider.pki.IssuedCertificate;
import cn.byteforce.seedj.provider.pki.KeyMaterial;
import cn.byteforce.seedj.provider.pki.PkiClosedException;
import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.provider.pki.PkiInfo;
import cn.byteforce.seedj.provider.pki.PkiProvider;
import cn.byteforce.seedj.provider.pki.RevocationReason;
import cn.byteforce.seedj.provider.pki.SslConfig;
import cn.byteforce.seedj.provider.pki.TidyRequest;
import cn.byteforce.seedj.transit.TransitSigner;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;

/**
 * 基于 Bouncy Castle 的 PKI 提供者（内置/快速启动实现）。
 *
 * <p>确定性签发：CA 从 p12 固定加载、序列号由请求内容 + 公钥派生、notBefore 截断到秒。相同请求与相同
 * keyPair 在所有实例上产生相同序列号与公钥 + EKU；配合共享的状态文件（{@link PkiStateFile}）交换签发、
 * 吊销与签名身份，多服务天然一致。
 *
 * <p>与 coord 适配的差异（见 {@code docs/guide/spi.md}）：本地实现没有 role/issuer 策略，{@code roleName}
 * 只做非空校验；{@code sign}/{@code verify} 委托本地 Transit（不支持 prehashed 与 PSS）；私钥可以导出，所以
 * keyMaterial 永远是 {@link KeyMaterial.External}。
 */
public class BcPkiProvider implements PkiProvider {

    private static final Duration SIGNING_VALIDITY = Duration.ofDays(3650);
    private static final int SIGNING_KEY_SIZE = 2048;
    private static final Duration CRL_VALIDITY = Duration.ofHours(24);
    private static final Duration DEFAULT_TIDY_RETENTION = Duration.ofHours(24);
    private static final Pattern SIGNING_ALIAS_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 仅用于内存 KeyStore，随机生成，不落盘。 */
    private final char[] keyStorePassword = UUID.randomUUID().toString().toCharArray();

    private final X500Name caName;
    private final KeyPair caKeyPair;
    private final X509Certificate caCertificate;
    private final PkiStateFile stateFile;
    private final TransitSigner transitSigner;
    private final Clock clock;

    private volatile boolean closed;

    public BcPkiProvider(String caResource, char[] password, String alias, Path stateFile) {
        this(caResource, password, alias, stateFile, null);
    }

    public BcPkiProvider(
            String caResource,
            char[] password,
            String alias,
            Path stateFile,
            TransitSigner transitSigner) {
        this(caResource, password, alias, new PkiStateFile(stateFile), transitSigner);
    }

    BcPkiProvider(String caResource, char[] password, String alias, PkiStateFile stateFile) {
        this(caResource, password, alias, stateFile, null);
    }

    BcPkiProvider(
            String caResource,
            char[] password,
            String alias,
            PkiStateFile stateFile,
            TransitSigner transitSigner) {
        this(caResource, password, alias, stateFile, transitSigner, Clock.systemUTC());
    }

    BcPkiProvider(
            String caResource,
            char[] password,
            String alias,
            PkiStateFile stateFile,
            TransitSigner transitSigner,
            Clock clock) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = openResource(caResource)) {
                ks.load(in, password);
            }
            this.caCertificate = (X509Certificate) ks.getCertificate(alias);
            if (caCertificate == null) {
                throw new PkiException(
                        PkiErrorCode.CA_UNAVAILABLE, "CA keystore 中找不到别名: " + alias);
            }
            this.caKeyPair =
                    new KeyPair(
                            caCertificate.getPublicKey(),
                            (java.security.PrivateKey) ks.getKey(alias, password));
            // 直接用 DER 编码构建 X500Name，避免 getName() 往返导致 RDN 逆序
            this.caName =
                    X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded());
            this.stateFile = stateFile;
            this.transitSigner = transitSigner;
            this.clock = clock;
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.CA_UNAVAILABLE, "加载 CA 失败: " + caResource, e);
        }
    }

    // ==================== 签发 ====================

    @Override
    public IssuedCertificate issue(String roleName, CertificateRequest request) {
        ensureOpen();
        requireRole(roleName);
        String subject = request.requireSubject();
        KeyPair keyPair = generateKeyPair();
        X509Certificate certificate =
                BcPkiCertificates.issue(
                        caName,
                        caKeyPair.getPrivate(),
                        BcPkiCertificates.subjectName(subject),
                        keyPair.getPublic(),
                        BcPkiCertificates.subjectAltNames(request),
                        request.validity(),
                        request.usage(),
                        deterministicSerial(subject, request, keyPair.getPublic()),
                        clock.instant());
        stateFile.recordIssued(certificate);
        return materialize(certificate, keyPair);
    }

    @Override
    public IssuedCertificate signCsr(
            String roleName, byte[] csrDer, CertificateRequest request) {
        ensureOpen();
        requireRole(roleName);
        if (csrDer == null || csrDer.length == 0) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "CSR 不能为空");
        }
        BcPkiCertificates.Csr csr = BcPkiCertificates.parseCsr(csrDer);
        CertificateRequest effective =
                request == null
                        ? CertificateRequest.csr(null, CertificateUsage.BOTH)
                        : request;
        X509Certificate certificate =
                BcPkiCertificates.issue(
                        caName,
                        caKeyPair.getPrivate(),
                        csr.subject(),
                        csr.publicKey(),
                        csr.altNames(),
                        effective.validity(),
                        effective.usage(),
                        deterministicSerial(
                                csr.subject().toString(), effective, csr.publicKey()),
                        clock.instant());
        stateFile.recordIssued(certificate);
        return new IssuedCertificate(
                certificate, List.of(certificate, caCertificate), new KeyMaterial.External());
    }

    // ==================== 签名身份 ====================

    @Override
    public IssuedCertificate signingIdentity(String alias) {
        ensureOpen();
        requireAlias(alias);
        KeyPair keyPair = stateFile.getOrCreateKeyPair(alias, SIGNING_KEY_SIZE);
        X509Certificate stored = stateFile.identityCertificate(alias).orElse(null);
        if (stored != null) {
            BigInteger serial = stored.getSerialNumber();
            if (stateFile.revoked(serial)) {
                throw new CertificateRevokedException(alias, serial);
            }
            if (!stored.getNotAfter().toInstant().isBefore(clock.instant())) {
                return materialize(stored, keyPair);
            }
        }
        return issueSigningIdentity(alias, keyPair);
    }

    @Override
    public IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason) {
        ensureOpen();
        requireAlias(alias);
        if (reason == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "reason 不能为空");
        }
        X509Certificate stored = stateFile.identityCertificate(alias).orElse(null);
        if (stored == null) {
            return signingIdentity(alias);
        }
        BigInteger oldSerial = stored.getSerialNumber();
        if (!stateFile.revoked(oldSerial)) {
            stateFile.recordRevoked(oldSerial, reason);
        }
        KeyPair rotated = stateFile.rotateKeyPair(alias, SIGNING_KEY_SIZE);
        return issueSigningIdentity(alias, rotated);
    }

    private IssuedCertificate issueSigningIdentity(String alias, KeyPair keyPair) {
        String subject = "CN=" + alias + ",O=Byteforce";
        X509Certificate certificate =
                BcPkiCertificates.issue(
                        caName,
                        caKeyPair.getPrivate(),
                        BcPkiCertificates.subjectName(subject),
                        keyPair.getPublic(),
                        null,
                        SIGNING_VALIDITY,
                        CertificateUsage.BOTH,
                        deterministicSerial(alias, null, keyPair.getPublic()),
                        clock.instant());
        stateFile.recordIssued(certificate);
        stateFile.putIdentityCertificate(alias, certificate);
        return materialize(certificate, keyPair);
    }

    private IssuedCertificate materialize(X509Certificate certificate, KeyPair keyPair) {
        return new IssuedCertificate(
                certificate,
                List.of(certificate, caCertificate),
                new KeyMaterial.External(keyPair.getPrivate()));
    }

    private static void requireAlias(String alias) {
        if (alias == null || !SIGNING_ALIAS_PATTERN.matcher(alias).matches()) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST,
                    "alias 必须匹配 " + SIGNING_ALIAS_PATTERN.pattern() + ": " + alias);
        }
    }

    private static void requireRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "roleName 不能为空");
        }
    }

    // ==================== 吊销 / CRL ====================

    @Override
    public void revoke(BigInteger serialNumber, RevocationReason reason) {
        ensureOpen();
        if (serialNumber == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "serialNumber 不能为空");
        }
        stateFile.recordRevoked(
                serialNumber, reason == null ? RevocationReason.UNSPECIFIED : reason);
    }

    @Override
    public byte[] getCrl() {
        ensureOpen();
        return BcPkiCertificates.crl(
                caName,
                caKeyPair.getPrivate(),
                stateFile.revokedEntries(),
                stateFile.crlNumber(),
                clock.instant(),
                CRL_VALIDITY);
    }

    @Override
    public void rotateCrl() {
        ensureOpen();
        stateFile.nextCrlNumber();
    }

    @Override
    public void tidy(TidyRequest request) {
        ensureOpen();
        Duration retention =
                request == null || request.retention() == null
                        ? DEFAULT_TIDY_RETENTION
                        : request.retention();
        stateFile.tidy(clock.instant().minus(retention));
    }

    // ==================== 证书 / CA 查询 ====================

    @Override
    public Optional<X509Certificate> readCertificate(BigInteger serialNumber) {
        ensureOpen();
        if (serialNumber == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "serialNumber 不能为空");
        }
        return stateFile.certificate(serialNumber);
    }

    @Override
    public X509Certificate issuerCertificate(String issuerRef) {
        ensureOpen();
        requireIssuer(issuerRef);
        return caCertificate;
    }

    @Override
    public List<X509Certificate> caChain(String issuerRef) {
        ensureOpen();
        requireIssuer(issuerRef);
        return List.of(caCertificate);
    }

    private void requireIssuer(String issuerRef) {
        if (issuerRef != null && !issuerRef.isBlank() && !"default".equalsIgnoreCase(issuerRef)) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST,
                    "local PKI has a single issuer; only 'default' is accepted: " + issuerRef);
        }
    }

    // ==================== 签名 / 验签（委托本地 Transit） ====================

    @Override
    public byte[] sign(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data) {
        ensureOpen();
        return BcPkiTransit.sign(
                transitSigner, keyName, hashAlgorithm, prehashed, signatureAlgorithm, data);
    }

    @Override
    public boolean verify(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data,
            byte[] signature) {
        ensureOpen();
        return BcPkiTransit.verify(
                transitSigner,
                keyName,
                hashAlgorithm,
                prehashed,
                signatureAlgorithm,
                data,
                signature);
    }

    // ==================== TLS ====================

    @Override
    public SSLContext sslContext(IssuedCertificate identity) {
        ensureOpen();
        try {
            TrustManagerFactory trustManagers = trustManagers();
            KeyManager[] keyManagers = identity == null ? null : keyManagers(identity);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "构建 SSLContext 失败", e);
        }
    }

    @Override
    public SslConfig sslConfig(IssuedCertificate identity, ClientAuth clientAuth) {
        ensureOpen();
        if (identity == null) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "sslConfig requires a non-null identity");
        }
        return new SslConfig(
                identity.chain(),
                identity.keyMaterial(),
                clientAuth == null ? ClientAuth.NONE : clientAuth,
                List.of(caCertificate));
    }

    private TrustManagerFactory trustManagers() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCertificate);
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    private KeyManager[] keyManagers(IssuedCertificate identity) throws Exception {
        if (!(identity.keyMaterial() instanceof KeyMaterial.External external)
                || !external.attached()) {
            throw new IllegalStateException(
                    "coord-managed keys cannot build a standard SSLContext; use sslConfig + sign");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        List<X509Certificate> chain =
                identity.chain().isEmpty() ? List.of(identity.certificate()) : identity.chain();
        keyStore.setKeyEntry(
                "identity",
                external.privateKey(),
                keyStorePassword,
                chain.toArray(Certificate[]::new));
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyStorePassword);
        return kmf.getKeyManagers();
    }

    // ==================== 生命周期 ====================

    @Override
    public PkiInfo info() {
        ensureOpen();
        return new PkiInfo(
                "local",
                "default",
                caCertificate.getSubjectX500Principal().getName(),
                caCertificate.getNotAfter().toInstant(),
                false);
    }

    @Override
    public HealthStatus health() {
        if (closed) {
            return HealthStatus.unknown("local pki provider is closed");
        }
        return HealthStatus.up("local CA " + caCertificate.getSubjectX500Principal().getName());
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new PkiClosedException("local");
        }
    }

    // ==================== 内部工具 ====================

    private BigInteger deterministicSerial(
            String subject, CertificateRequest request, java.security.PublicKey publicKey) {
        try {
            Duration validity =
                    request == null || request.validity() == null
                            ? BcPkiCertificates.DEFAULT_VALIDITY
                            : request.validity();
            String canonical =
                    String.join(
                            "|",
                            subject,
                            request == null ? "" : String.join(",", request.dnsNames()),
                            request == null ? "" : String.join(",", request.ipAddresses()),
                            String.valueOf(validity.toSeconds()),
                            String.valueOf(
                                    request == null ? CertificateUsage.BOTH : request.usage()),
                            Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, Arrays.copyOf(digest, 16));
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "派生证书序列号失败", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "生成 RSA 密钥对失败", e);
        }
    }

    private static InputStream openResource(String resource) throws Exception {
        if (resource.startsWith("classpath:")) {
            String path = resource.substring("classpath:".length());
            InputStream in =
                    BcPkiProvider.class.getResourceAsStream(
                            path.startsWith("/") ? path : "/" + path);
            if (in == null) {
                throw new PkiException("找不到 classpath 资源: " + resource);
            }
            return in;
        }
        if (resource.startsWith("file:")) {
            return Files.newInputStream(Path.of(java.net.URI.create(resource)));
        }
        return Files.newInputStream(Path.of(resource));
    }
}
