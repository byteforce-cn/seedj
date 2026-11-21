package cn.byteforce.seedj.infra.local.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.provider.pki.CertificateRequest;
import cn.byteforce.seedj.provider.pki.CertificateRevokedException;
import cn.byteforce.seedj.provider.pki.CertificateUsage;
import cn.byteforce.seedj.provider.pki.IssuedCertificate;
import cn.byteforce.seedj.provider.pki.KeyMaterial;
import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.provider.pki.RevocationReason;
import cn.byteforce.seedj.provider.pki.TidyRequest;
import cn.byteforce.seedj.transit.CreateKeyRequest;
import cn.byteforce.seedj.transit.KeyType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BcPkiProviderTest {

    private static final char[] PASSWORD = "test-password".toCharArray();
    private static final String CA_ALIAS = "pki-test-ca";

    @TempDir static Path tempDir;
    private static Path caP12;

    private Path stateFile;
    private PkiStateFile stateStore;
    private BcPkiProvider pki;

    @BeforeAll
    static void generateCa() throws Exception {
        caP12 = createCaP12(tempDir);
    }

    @BeforeEach
    void setUp() {
        stateFile = tempDir.resolve("pki-state-" + UUID.randomUUID() + ".properties");
        stateStore = new PkiStateFile(stateFile);
        pki = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateStore);
    }

    // ==================== CA / 签发 ====================

    @Test
    @DisplayName("CA 证书自签且带 CA 标志")
    void caCertificateIsSelfSignedCa() throws Exception {
        X509Certificate ca = pki.issuerCertificate("default");

        assertThat(ca.getSubjectX500Principal().getName()).contains("SeedJ Test Root CA");
        ca.verify(ca.getPublicKey());
        assertThat(ca.getBasicConstraints()).isGreaterThanOrEqualTo(0);
        assertThat(pki.caChain("default")).containsExactly(ca);
    }

    @Test
    @DisplayName("服务端证书带 serverAuth EKU 与 SAN")
    void issueServerHasServerAuthEkuAndSan() throws Exception {
        IssuedCertificate issued =
                pki.issue(
                        "server",
                        CertificateRequest.server(
                                "CN=localhost", List.of("localhost"), List.of("127.0.0.1")));
        X509Certificate cert = issued.certificate();
        cert.verify(pki.issuerCertificate("default").getPublicKey());
        assertThat(cert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");
        assertThat(cert.getSubjectAlternativeNames()).hasSize(2);
        assertThat(issued.keyMaterial()).isInstanceOf(KeyMaterial.External.class);
        assertThat(((KeyMaterial.External) issued.keyMaterial()).attached()).isTrue();
    }

    @Test
    @DisplayName("客户端证书带 clientAuth EKU 且无 SAN")
    void issueClientHasClientAuthEku() throws Exception {
        IssuedCertificate issued =
                pki.issue("client", CertificateRequest.client("CN=im-node-1"));
        X509Certificate cert = issued.certificate();
        cert.verify(pki.issuerCertificate("default").getPublicKey());
        assertThat(cert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.2");
        assertThat(cert.getSubjectAlternativeNames()).isNull();
    }

    @Test
    @DisplayName("CSR 签发保留 CN/SAN，返回 External 未绑定钥；withPrivateKey 后可建 TLS 身份")
    void signCsrKeepsSubjectAndSan() throws Exception {
        KeyPair keyPair = generateKeyPair();
        PKCS10CertificationRequest csr = csr("CN=csr-svc", keyPair, "csr.local");

        IssuedCertificate issued =
                pki.signCsr(
                        "client",
                        csr.getEncoded(),
                        CertificateRequest.csr(Duration.ofDays(30), CertificateUsage.CLIENT));

        assertThat(issued.certificate().getSubjectX500Principal().getName()).contains("CN=csr-svc");
        assertThat(issued.certificate().getSubjectAlternativeNames())
                .anySatisfy(
                        entry -> {
                            assertThat(entry.get(0)).isEqualTo(2);
                            assertThat(entry.get(1)).isEqualTo("csr.local");
                        });
        assertThat(issued.keyMaterial()).isInstanceOf(KeyMaterial.External.class);
        assertThat(((KeyMaterial.External) issued.keyMaterial()).attached()).isFalse();
        assertThat(issued.withPrivateKey(keyPair.getPrivate())).isNotNull();
        assertThat(pki.sslContext(issued.withPrivateKey(keyPair.getPrivate()))).isNotNull();
    }

    @Test
    @DisplayName("非法 CSR 统一 INVALID_REQUEST")
    void signCsrRejectsGarbage() {
        assertThatThrownBy(
                        () ->
                                pki.signCsr(
                                        "client",
                                        new byte[] {1, 2, 3},
                                        CertificateRequest.csr(null, null)))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
    }

    // ==================== 吊销 / CRL ====================

    @Test
    @DisplayName("吊销后证书仍可按 serial 读取，CRL 带原因为首个")
    void revokeKeepsCertificateAndAddsCrlEntry() throws Exception {
        IssuedCertificate issued = pki.issue("client", CertificateRequest.client("CN=client-1"));
        pki.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);
        pki.revoke(issued.serialNumber(), RevocationReason.SUPERSEDED);

        assertThat(pki.readCertificate(issued.serialNumber())).isPresent();
        X509CRL crl = crl(pki.getCrl());
        assertThat(crl.getRevokedCertificate(issued.serialNumber())).isNotNull();
        assertThat(crl.getRevokedCertificate(issued.serialNumber()).getRevocationReason())
                .isEqualTo(java.security.cert.CRLReason.KEY_COMPROMISE);
    }

    @Test
    @DisplayName("未知 serial：读取为空，吊销抛 NOT_FOUND")
    void unknownSerial() {
        BigInteger unknown = new BigInteger("deadbeef", 16);

        assertThat(pki.readCertificate(unknown)).isEmpty();
        assertThatThrownBy(() -> pki.revoke(unknown, RevocationReason.UNSPECIFIED))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("tidy 删除过期未吊销证书，保留吊销记录")
    void tidyRemovesExpiredButKeepsRevoked() {
        IssuedCertificate expired =
                pki.issue(
                        "client",
                        new CertificateRequest(
                                "CN=expired",
                                List.of(),
                                List.of(),
                                Duration.ofDays(1),
                                CertificateUsage.CLIENT));
        IssuedCertificate revoked =
                pki.issue(
                        "client",
                        new CertificateRequest(
                                "CN=revoked",
                                List.of(),
                                List.of(),
                                Duration.ofDays(1),
                                CertificateUsage.CLIENT));
        pki.revoke(revoked.serialNumber(), RevocationReason.CESSATION_OF_OPERATION);

        BcPkiProvider later =
                new BcPkiProvider(
                        caP12.toString(),
                        PASSWORD,
                        CA_ALIAS,
                        stateStore,
                        null,
                        Clock.offset(Clock.systemUTC(), Duration.ofDays(10)));
        later.tidy(TidyRequest.retainFor(Duration.ZERO));

        assertThat(later.readCertificate(expired.serialNumber())).isEmpty();
        assertThat(later.readCertificate(revoked.serialNumber())).isPresent();
    }

    // ==================== TLS ====================

    @Test
    @DisplayName("sslContext(null) 只信任 CA；sslContext(identity) 携带身份")
    void sslContext() {
        IssuedCertificate client = pki.issue("client", CertificateRequest.client("CN=client-1"));

        assertThat(pki.sslContext(null)).isNotNull();
        assertThat(pki.sslContext(client)).isNotNull();
        assertThat(pki.sslConfig(client, null).delegatingSigning()).isFalse();
    }

    // ==================== 多实例 ====================

    @Test
    @DisplayName("两个实例共享状态文件时签发/吊销一致")
    void stateSharedBetweenInstances() {
        BcPkiProvider other =
                new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);

        IssuedCertificate issued = pki.issue("client", CertificateRequest.client("CN=shared-1"));
        assertThat(other.readCertificate(issued.serialNumber())).isPresent();

        other.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);
        assertThat(crl(pki.getCrl()).getRevokedCertificate(issued.serialNumber())).isNotNull();
    }

    @Test
    @DisplayName("clear() 清空签发记录")
    void clearResetsState() {
        IssuedCertificate issued = pki.issue("client", CertificateRequest.client("CN=to-clear"));
        stateStore.clear();

        assertThat(pki.readCertificate(issued.serialNumber())).isEmpty();
    }

    // ==================== 签名身份 ====================

    @Test
    @DisplayName("signingIdentity 幂等：serial、公钥、私钥与有效期都不变")
    void signingIdentityIsIdempotent() {
        IssuedCertificate first = pki.signingIdentity("test-signer");
        IssuedCertificate second = pki.signingIdentity("test-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
        assertThat(((KeyMaterial.External) second.keyMaterial()).privateKey())
                .isEqualTo(((KeyMaterial.External) first.keyMaterial()).privateKey());
        assertThat(second.notAfter()).isEqualTo(first.notAfter());
    }

    @Test
    @DisplayName("共享状态文件时 signingIdentity 返回同一身份")
    void signingIdentitySharedAcrossInstances() {
        IssuedCertificate first = pki.signingIdentity("shared-signer");

        BcPkiProvider other =
                new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        IssuedCertificate second = other.signingIdentity("shared-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
    }

    @Test
    @DisplayName("并发首次使用收敛到同一 serial")
    void signingIdentityConvergesUnderConcurrency() throws Exception {
        BcPkiProvider other =
                new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<BigInteger>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                BcPkiProvider target = (i % 2 == 0) ? pki : other;
                futures.add(
                        pool.submit(
                                () -> target.signingIdentity("concurrent-signer").serialNumber()));
            }
            List<BigInteger> serials = new ArrayList<>();
            for (Future<BigInteger> future : futures) {
                serials.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(serials).containsOnly(serials.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("非法 alias 抛 INVALID_REQUEST")
    void signingIdentityRejectsInvalidAlias() {
        for (String alias : new String[] {null, " ", "bad,alias", "bad=alias"}) {
            assertThatThrownBy(() -> pki.signingIdentity(alias))
                    .isInstanceOf(PkiException.class)
                    .extracting(e -> ((PkiException) e).getErrorCode())
                    .isEqualTo(PkiErrorCode.INVALID_REQUEST);
        }
    }

    @Test
    @DisplayName("过期后续期：复用同一密钥与 serial，notAfter 顺延")
    void signingIdentityRenewsExpiredCertificate() {
        IssuedCertificate first = pki.signingIdentity("renew-signer");

        BcPkiProvider later =
                new BcPkiProvider(
                        caP12.toString(),
                        PASSWORD,
                        CA_ALIAS,
                        stateStore,
                        null,
                        Clock.offset(Clock.systemUTC(), Duration.ofDays(3651)));
        IssuedCertificate renewed = later.signingIdentity("renew-signer");

        assertThat(renewed.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(renewed.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
        assertThat(renewed.notAfter()).isAfter(first.notAfter());
    }

    @Test
    @DisplayName("身份证书丢失时用已有密钥重建，serial 不变")
    void signingIdentityRebuildsMissingCertificate() throws Exception {
        IssuedCertificate first = pki.signingIdentity("repair-signer");
        removeStateKeys("identity.repair-signer.cert");

        IssuedCertificate rebuilt = pki.signingIdentity("repair-signer");

        assertThat(rebuilt.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(((KeyMaterial.External) rebuilt.keyMaterial()).privateKey())
                .isEqualTo(((KeyMaterial.External) first.keyMaterial()).privateKey());
    }

    @Test
    @DisplayName("吊销后抛 CertificateRevokedException，不静默恢复")
    void revokedIdentityIsNotResurrected() {
        IssuedCertificate issued = pki.signingIdentity("revoked-signer");
        pki.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThatThrownBy(() -> pki.signingIdentity("revoked-signer"))
                .isInstanceOf(CertificateRevokedException.class)
                .hasMessageContaining("rotateSigningIdentity");
    }

    @Test
    @DisplayName("rotateSigningIdentity 吊销旧身份、换钥并返回新身份")
    void rotateSigningIdentity() {
        IssuedCertificate old = pki.signingIdentity("rotate-signer");

        IssuedCertificate rotated =
                pki.rotateSigningIdentity("rotate-signer", RevocationReason.SUPERSEDED);

        assertThat(rotated.serialNumber()).isNotEqualTo(old.serialNumber());
        assertThat(rotated.certificate().getPublicKey())
                .isNotEqualTo(old.certificate().getPublicKey());
        assertThat(crl(pki.getCrl()).getRevokedCertificate(old.serialNumber())).isNotNull();
        assertThat(pki.signingIdentity("rotate-signer").serialNumber())
                .isEqualTo(rotated.serialNumber());
    }

    @Test
    @DisplayName("rotate 未使用的 alias 等价于首次签发")
    void rotateWithoutExistingIdentity() {
        IssuedCertificate rotated =
                pki.rotateSigningIdentity("fresh-signer", RevocationReason.UNSPECIFIED);

        assertThat(rotated.serialNumber()).isNotNull();
        assertThat(pki.signingIdentity("fresh-signer").serialNumber())
                .isEqualTo(rotated.serialNumber());
    }

    // ==================== Transit 签名 ====================

    @Test
    @DisplayName("sign/verify 委托本地 Transit，签名可验证")
    void signAndVerifyViaTransit() {
        cn.byteforce.seedj.infra.local.transit.BcTransitProvider transit =
                new cn.byteforce.seedj.infra.local.transit.BcTransitProvider();
        transit.keyAdmin().createKey(CreateKeyRequest.of("sig-key", KeyType.RSA_2048));
        BcPkiProvider signer =
                new BcPkiProvider(
                        caP12.toString(), PASSWORD, CA_ALIAS, stateStore, transit.signer());

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign("sig-key", "sha256", false, "pkcs1v15", data);

        assertThat(signer.verify("sig-key", "sha256", false, "pkcs1v15", data, signature)).isTrue();
        assertThat(signer.verify("sig-key", "sha256", false, "pkcs1v15", new byte[] {1}, signature))
                .isFalse();
    }

    @Test
    @DisplayName("本地 Transit 不支持 prehashed：显式 UNSUPPORTED")
    void prehashedIsUnsupported() {
        BcPkiProvider signer =
                new BcPkiProvider(
                        caP12.toString(),
                        PASSWORD,
                        CA_ALIAS,
                        stateStore,
                        new cn.byteforce.seedj.infra.local.transit.BcTransitProvider().signer());

        assertThatThrownBy(
                        () ->
                                signer.sign(
                                        "sig-key",
                                        "sha256",
                                        true,
                                        "pkcs1v15",
                                        new byte[] {1, 2, 3}))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.UNSUPPORTED);
    }

    // ==================== 生命周期 ====================

    @Test
    @DisplayName("info/health/close 语义")
    void lifecycle() {
        assertThat(pki.info().provider()).isEqualTo("local");
        assertThat(pki.health().healthy()).isTrue();

        pki.close();
        pki.close();

        assertThat(pki.health().healthy()).isFalse();
        assertThatThrownBy(() -> pki.getCrl()).isInstanceOf(PkiException.class);
    }

    // ==================== 工具 ====================

    private void removeStateKeys(String prefix) throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        }
        props.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix))
                .toList()
                .forEach(props::remove);
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "test");
        }
    }

    private static X509CRL crl(byte[] der) {
        try {
            return (X509CRL)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCRL(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static PKCS10CertificationRequest csr(
            String subject, KeyPair keyPair, String dnsName) throws Exception {
        Extensions extensions =
                new Extensions(
                        new Extension(
                                Extension.subjectAlternativeName,
                                false,
                                new GeneralNames(
                                                new GeneralName(GeneralName.dNSName, dnsName))
                                        .getEncoded()));
        return new JcaPKCS10CertificationRequestBuilder(
                        new X500Name(subject), keyPair.getPublic())
                .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
                .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                                .build(keyPair.getPrivate()));
    }

    static Path createCaP12(Path dir) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=SeedJ Test Root CA, O=Byteforce");
        Date now = new Date();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.valueOf(System.nanoTime()),
                        now,
                        new Date(now.getTime() + Duration.ofDays(3650).toMillis()),
                        subject,
                        keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        X509Certificate certificate =
                new JcaX509CertificateConverter()
                        .getCertificate(
                                builder.build(
                                        new JcaContentSignerBuilder("SHA256withRSA")
                                                .build(keyPair.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                CA_ALIAS,
                keyPair.getPrivate(),
                PASSWORD,
                new Certificate[] {certificate});
        Path file = dir.resolve("ca-" + UUID.randomUUID() + ".p12");
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, PASSWORD);
        }
        return file;
    }
}
