package cn.byteforce.seedj.pki;

import cn.byteforce.seedj.pki.PkiProvider.CertificateRequest;
import cn.byteforce.seedj.pki.PkiProvider.CertificateStatus;
import cn.byteforce.seedj.pki.PkiProvider.CertificateUsage;
import cn.byteforce.seedj.pki.PkiProvider.IssuedCertificate;
import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 基于 Bouncy Castle 的 PKI 提供者（内置/快速启动实现）。
 *
 * <p>确定性签发：CA 从 p12 固定加载、序列号由请求内容 + 公钥派生、notBefore 截断到秒。
 * 相同 {@link CertificateRequest} 与相同 keyPair 在所有实例上产生相同序列号与公钥 + EKU；
 * 配合共享的状态文件（{@link PkiStateFile}）交换签发/吊销与签名身份，多服务天然一致。
 *
 * <p>序列号纳入公钥摘要，避免“相同 subject/usage 但不同 keyPair”产生相同 serial，
 * 进而导致 JWK {@code kid} 冲突。
 */
public class BcPkiProvider implements PkiProvider {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(365);
    private static final int SIGNING_KEY_SIZE = 2048;
    private static final Duration SIGNING_VALIDITY = Duration.ofDays(3650);
    private static final Pattern SIGNING_ALIAS_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 仅用于内存 KeyStore，随机生成，不落盘。 */
    private final char[] keyStorePassword = UUID.randomUUID().toString().toCharArray();

    private final X500Name caName;
    private final KeyPair caKeyPair;
    private final X509Certificate caCertificate;
    private final PkiStateFile stateFile;

    public BcPkiProvider(String caResource, char[] password, String alias, Path stateFile) {
        this(caResource, password, alias, new PkiStateFile(stateFile));
    }

    BcPkiProvider(String caResource, char[] password, String alias, PkiStateFile stateFile) {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = openResource(caResource)) {
                ks.load(in, password);
            }
            this.caCertificate = (X509Certificate) ks.getCertificate(alias);
            if (caCertificate == null) {
                throw new PkiException(PkiErrorCode.CA_UNAVAILABLE, "CA keystore 中找不到别名: " + alias);
            }
            this.caKeyPair = new KeyPair(caCertificate.getPublicKey(),
                    (PrivateKey) ks.getKey(alias, password));
            // 直接用 DER 编码构建 X500Name，避免 getName() 往返导致 RDN 逆序
            this.caName = X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded());
            this.stateFile = stateFile;
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.CA_UNAVAILABLE, "加载 CA 失败: " + caResource, e);
        }
    }

    // ==================== 签发 ====================

    @Override
    public IssuedCertificate issue(CertificateRequest request) {
        try {
            KeyPair keyPair = request.keyPair() != null ? request.keyPair() : generateKeyPair();
            BigInteger serial = deterministicSerial(request, keyPair.getPublic());
            Instant notBefore = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Duration validity = request.validity() != null ? request.validity() : DEFAULT_VALIDITY;
            Instant notAfter = notBefore.plus(validity);

            CertificateUsage usage = request.usage() != null
                    ? request.usage() : CertificateUsage.BOTH;

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caName, serial, Date.from(notBefore), Date.from(notAfter),
                    new X500Name(request.subject()), keyPair.getPublic());

            // BasicConstraints: 终端实体
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            // KeyUsage 按 usage 设置
            int keyUsageBits = KeyUsage.digitalSignature;
            if (usage != CertificateUsage.CLIENT) {
                keyUsageBits |= KeyUsage.keyEncipherment;
            }
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));

            // ExtendedKeyUsage：SERVER / CLIENT 显式声明，BOTH 不声明（两端都可接受）
            if (usage == CertificateUsage.SERVER) {
                builder.addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            } else if (usage == CertificateUsage.CLIENT) {
                builder.addExtension(Extension.extendedKeyUsage, false,
                        new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            }

            // SubjectAlternativeName
            List<GeneralName> sans = new ArrayList<>();
            if (request.dnsNames() != null) {
                for (String dns : request.dnsNames()) {
                    sans.add(new GeneralName(GeneralName.dNSName, dns));
                }
            }
            if (request.ipAddresses() != null) {
                for (String ip : request.ipAddresses()) {
                    sans.add(new GeneralName(GeneralName.iPAddress, ip));
                }
            }
            if (!sans.isEmpty()) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(sans.toArray(new GeneralName[0])));
            }

            var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                    .build(caKeyPair.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(signer));

            IssuedCertificate record = new IssuedCertificate(
                    cert, List.of(cert, caCertificate),
                    keyPair.getPrivate(), serial.toString(16), notBefore, notAfter);
            stateFile.recordIssued(record);
            return record;
        } catch (Exception e) {
            throw new PkiException("签发证书失败: " + request.subject(), e);
        }
    }

    // ==================== 签名身份 ====================

    @Override
    public IssuedCertificate signingIdentity(String alias) {
        requireAlias(alias);
        KeyPair keyPair = stateFile.getOrCreateKeyPair(alias, SIGNING_KEY_SIZE);
        X509Certificate stored = stateFile.identityCertificate(alias).orElse(null);
        if (stored != null) {
            CertificateStatus status = stateFile.status(serialOf(stored));
            if (status == CertificateStatus.REVOKED) {
                throw new PkiException(
                        "签名身份已被吊销，需调用 rotateSigningIdentity(alias, reason): " + alias);
            }
            if (status == CertificateStatus.VALID) {
                return materialize(stored, keyPair);
            }
        }
        return issueSigningIdentity(alias, keyPair);
    }

    @Override
    public IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason) {
        requireAlias(alias);
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        X509Certificate stored = stateFile.identityCertificate(alias).orElse(null);
        if (stored == null) {
            return signingIdentity(alias);
        }
        String oldSerial = serialOf(stored);
        CertificateStatus status = stateFile.status(oldSerial);
        if (status == CertificateStatus.VALID || status == CertificateStatus.EXPIRED) {
            stateFile.recordRevoked(oldSerial, reason);
        }
        KeyPair rotated = stateFile.rotateKeyPair(alias, SIGNING_KEY_SIZE);
        return issueSigningIdentity(alias, rotated);
    }

    private IssuedCertificate issueSigningIdentity(String alias, KeyPair keyPair) {
        IssuedCertificate issued = issue(new CertificateRequest(
                "CN=" + alias + ",O=Byteforce",
                List.of(), List.of(), SIGNING_VALIDITY, keyPair, CertificateUsage.BOTH));
        stateFile.putIdentityCertificate(alias, issued.certificate());
        return issued;
    }

    private IssuedCertificate materialize(X509Certificate certificate, KeyPair keyPair) {
        return new IssuedCertificate(
                certificate, List.of(certificate, caCertificate), keyPair.getPrivate(),
                serialOf(certificate),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant());
    }

    private static String serialOf(X509Certificate certificate) {
        return certificate.getSerialNumber().toString(16);
    }

    private static String requireAlias(String alias) {
        if (alias == null || !SIGNING_ALIAS_PATTERN.matcher(alias).matches()) {
            throw new IllegalArgumentException(
                    "alias 必须匹配 " + SIGNING_ALIAS_PATTERN.pattern() + ": " + alias);
        }
        return alias;
    }

    // ==================== 吊销 / 状态 ====================

    @Override
    public void revoke(String serialNumber, RevocationReason reason) {
        stateFile.recordRevoked(serialNumber, reason);
    }

    @Override
    public CertificateStatus status(String serialNumber) {
        return stateFile.status(serialNumber);
    }

    @Override
    public X509Certificate caCertificate() {
        return caCertificate;
    }

    // ==================== SSLContext ====================

    @Override
    public SSLContext sslContext(IssuedCertificate identity) {
        try {
            TrustManager[] trustManagers = buildTrustManagers();
            KeyManager[] keyManagers = identity == null ? null : buildKeyManagers(identity);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, trustManagers, null);
            return ctx;
        } catch (Exception e) {
            throw new PkiException("构建 SSLContext 失败", e);
        }
    }

    private TrustManager[] buildTrustManagers() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", caCertificate);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf.getTrustManagers();
    }

    private KeyManager[] buildKeyManagers(IssuedCertificate identity) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        List<X509Certificate> chain = identity.chain() != null && !identity.chain().isEmpty()
                ? identity.chain()
                : List.of(identity.certificate());
        keyStore.setKeyEntry("identity", identity.privateKey(), keyStorePassword,
                chain.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyStorePassword);
        return kmf.getKeyManagers();
    }

    // ==================== 内部工具 ====================

    private BigInteger deterministicSerial(CertificateRequest request, PublicKey publicKey) throws Exception {
        Duration validity = request.validity() != null ? request.validity() : DEFAULT_VALIDITY;
        CertificateUsage usage = request.usage() != null
                ? request.usage() : CertificateUsage.BOTH;
        String canonical = String.join("|",
                request.subject(),
                request.dnsNames() == null ? "" : String.join(",", request.dnsNames()),
                request.ipAddresses() == null ? "" : String.join(",", request.ipAddresses()),
                String.valueOf(validity.toSeconds()),
                usage.name(),
                Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        return new BigInteger(1, Arrays.copyOf(digest, 16));
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new PkiException("生成 RSA 密钥对失败", e);
        }
    }

    private static InputStream openResource(String resource) throws Exception {
        if (resource.startsWith("classpath:")) {
            String path = resource.substring("classpath:".length());
            InputStream in = BcPkiProvider.class
                    .getResourceAsStream(path.startsWith("/") ? path : "/" + path);
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

    @Override
    public void close() {
        // 无连接池；状态文件按需读写，无需释放。
    }
}
