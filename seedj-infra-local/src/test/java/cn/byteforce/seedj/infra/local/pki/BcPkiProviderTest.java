package cn.byteforce.seedj.infra.local.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.pki.PkiException;
import cn.byteforce.seedj.pki.PkiProvider.CertificateRequest;
import cn.byteforce.seedj.pki.PkiProvider.CertificateStatus;
import cn.byteforce.seedj.pki.PkiProvider.CertificateUsage;
import cn.byteforce.seedj.pki.PkiProvider.IssuedCertificate;
import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
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

    @Test
    @DisplayName("CA 证书应为自签名且标记为 CA")
    void caCertificate_shouldBeSelfSignedAndCa() throws Exception {
        X509Certificate ca = pki.caCertificate();
        assertThat(ca).isNotNull();
        assertThat(ca.getSubjectX500Principal().getName()).contains("SeedJ Test Root CA");
        ca.verify(ca.getPublicKey());
        assertThat(ca.getBasicConstraints()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("签发服务端证书应包含 serverAuth EKU")
    void issue_server_shouldHaveServerAuthEku() throws Exception {
        IssuedCertificate issued =
                pki.issue(
                        CertificateRequest.server(
                                "CN=localhost", List.of("localhost"), List.of("127.0.0.1")));
        X509Certificate cert = issued.certificate();
        cert.verify(pki.caCertificate().getPublicKey());
        assertThat(cert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1"); // serverAuth
    }

    @Test
    @DisplayName("签发客户端证书应包含 clientAuth EKU 且无 SAN")
    void issue_client_shouldHaveClientAuthEku() throws Exception {
        IssuedCertificate issued = pki.issue(CertificateRequest.client("CN=im-node-1"));
        X509Certificate cert = issued.certificate();
        cert.verify(pki.caCertificate().getPublicKey());
        assertThat(cert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.2"); // clientAuth
        assertThat(cert.getSubjectAlternativeNames()).isNull();
    }

    @Test
    @DisplayName("相同请求 + 相同 keyPair 应产生相同序列号（确定性）")
    void issue_sameRequest_shouldProduceSameSerial() throws Exception {
        KeyPair keyPair = generateKeyPair();
        CertificateRequest request =
                new CertificateRequest(
                        "CN=deterministic",
                        List.of(),
                        List.of(),
                        Duration.ofDays(365),
                        keyPair,
                        CertificateUsage.CLIENT);
        IssuedCertificate a = pki.issue(request);
        IssuedCertificate b = pki.issue(request);
        assertThat(a.serialNumber()).isEqualTo(b.serialNumber());
    }

    @Test
    @DisplayName("相同请求但不同 keyPair 应产生不同序列号")
    void issue_sameRequestDifferentKeyPair_shouldProduceDifferentSerial() throws Exception {
        CertificateRequest aRequest =
                new CertificateRequest(
                        "CN=same-key",
                        List.of(),
                        List.of(),
                        Duration.ofDays(365),
                        generateKeyPair(),
                        CertificateUsage.CLIENT);
        CertificateRequest bRequest =
                new CertificateRequest(
                        "CN=same-key",
                        List.of(),
                        List.of(),
                        Duration.ofDays(365),
                        generateKeyPair(),
                        CertificateUsage.CLIENT);
        assertThat(pki.issue(aRequest).serialNumber())
                .isNotEqualTo(pki.issue(bRequest).serialNumber());
    }

    @Test
    @DisplayName("不同 usage 应产生不同序列号")
    void issue_differentUsage_shouldProduceDifferentSerial() throws Exception {
        KeyPair keyPair = generateKeyPair();
        IssuedCertificate a =
                pki.issue(
                        new CertificateRequest(
                                "CN=same",
                                List.of(),
                                List.of(),
                                Duration.ofDays(365),
                                keyPair,
                                CertificateUsage.SERVER));
        IssuedCertificate b =
                pki.issue(
                        new CertificateRequest(
                                "CN=same",
                                List.of(),
                                List.of(),
                                Duration.ofDays(365),
                                keyPair,
                                CertificateUsage.CLIENT));
        assertThat(a.serialNumber()).isNotEqualTo(b.serialNumber());
    }

    @Test
    @DisplayName("吊销后状态应为 REVOKED")
    void revoke_shouldUpdateStatus() {
        IssuedCertificate issued = pki.issue(CertificateRequest.client("CN=client-1"));
        assertThat(pki.status(issued.serialNumber())).isEqualTo(CertificateStatus.VALID);
        pki.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);
        assertThat(pki.status(issued.serialNumber())).isEqualTo(CertificateStatus.REVOKED);
    }

    @Test
    @DisplayName("未签发序列号状态应为 UNKNOWN")
    void status_unknownForUnknownSerial() {
        assertThat(pki.status("deadbeef")).isEqualTo(CertificateStatus.UNKNOWN);
    }

    @Test
    @DisplayName("吊销未签发序列号应抛异常")
    void revoke_unknownSerial_shouldThrow() {
        assertThatThrownBy(() -> pki.revoke("deadbeef", RevocationReason.UNSPECIFIED))
                .isInstanceOf(PkiException.class);
    }

    @Test
    @DisplayName("sslContext(null) 应返回仅信任 CA 的上下文")
    void sslContext_nullIdentity_shouldBeTrustOnly() {
        SSLContext ctx = pki.sslContext(null);
        assertThat(ctx).isNotNull();
    }

    @Test
    @DisplayName("sslContext(identity) 应返回携带身份证书的上下文")
    void sslContext_withIdentity_shouldCarryIdentity() {
        IssuedCertificate client = pki.issue(CertificateRequest.client("CN=client-1"));
        SSLContext ctx = pki.sslContext(client);
        assertThat(ctx).isNotNull();
    }

    @Test
    @DisplayName("两个实例共享同一状态文件时应看到一致的签发/吊销")
    void state_shared_between_instances() {
        PkiStateFile sharedStore = new PkiStateFile(stateFile);
        BcPkiProvider other = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, sharedStore);

        IssuedCertificate issued = pki.issue(CertificateRequest.client("CN=shared-1"));
        assertThat(other.status(issued.serialNumber())).isEqualTo(CertificateStatus.VALID);

        other.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);
        assertThat(pki.status(issued.serialNumber())).isEqualTo(CertificateStatus.REVOKED);
    }

    @Test
    @DisplayName("clear() 应清空全部状态")
    void clear_shouldResetState() {
        IssuedCertificate issued = pki.issue(CertificateRequest.client("CN=to-clear"));
        pki.revoke(issued.serialNumber(), RevocationReason.CESSATION);
        stateStore.clear();
        assertThat(pki.status(issued.serialNumber())).isEqualTo(CertificateStatus.UNKNOWN);
    }

    // ==================== 签名身份 ====================

    @Test
    @DisplayName("signingIdentity 首次调用应签发身份，再次调用应返回同一 serial、公钥与私钥")
    void signingIdentity_shouldBeIdempotent() {
        IssuedCertificate first = pki.signingIdentity("test-signer");
        IssuedCertificate second = pki.signingIdentity("test-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
        assertThat(second.privateKey()).isEqualTo(first.privateKey());
        assertThat(second.notAfter()).isEqualTo(first.notAfter());
    }

    @Test
    @DisplayName("两个实例共享状态文件时 signingIdentity 应返回同一身份")
    void signingIdentity_shouldBeSharedAcrossInstances() {
        IssuedCertificate first = pki.signingIdentity("shared-signer");

        BcPkiProvider other = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        IssuedCertificate second = other.signingIdentity("shared-signer");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
    }

    @Test
    @DisplayName("两个实例并发调用 signingIdentity 应收敛到同一 serial")
    void signingIdentity_shouldConvergeUnderConcurrentFirstUse() throws Exception {
        BcPkiProvider other = new BcPkiProvider(caP12.toString(), PASSWORD, CA_ALIAS, stateFile);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                BcPkiProvider target = (i % 2 == 0) ? pki : other;
                futures.add(
                        pool.submit(
                                () -> target.signingIdentity("concurrent-signer").serialNumber()));
            }
            List<String> serials = new ArrayList<>();
            for (Future<String> future : futures) {
                serials.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(serials).containsOnly(serials.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("alias 非法时应拒绝，不生成损坏的 DN")
    void signingIdentity_withInvalidAlias_shouldThrow() {
        assertThatThrownBy(() -> pki.signingIdentity(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity("bad,alias"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pki.signingIdentity("bad=alias"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("账本标记过期后 signingIdentity 应同密钥重签并顺延 notAfter")
    void signingIdentity_shouldRenewExpiredCertificate() throws IOException {
        IssuedCertificate first = pki.signingIdentity("renew-signer");
        markLedgerExpired(first.serialNumber());

        IssuedCertificate renewed = pki.signingIdentity("renew-signer");

        assertThat(renewed.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(renewed.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
        assertThat(renewed.notAfter()).isAfterOrEqualTo(first.notAfter());
        assertThat(pki.status(first.serialNumber())).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    @DisplayName("身份证书键丢失时应复用已有密钥重建，serial 不变")
    void signingIdentity_shouldRebuildWhenCertificateMissing() throws IOException {
        IssuedCertificate first = pki.signingIdentity("repair-signer");
        removeStateKeys("identity.repair-signer.cert");

        IssuedCertificate rebuilt = pki.signingIdentity("repair-signer");

        assertThat(rebuilt.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(rebuilt.privateKey()).isEqualTo(first.privateKey());
    }

    @Test
    @DisplayName("账本记录丢失时 signingIdentity 应重建账本而不是抛异常")
    void signingIdentity_shouldRebuildWhenLedgerMissing() throws IOException {
        IssuedCertificate first = pki.signingIdentity("ledger-signer");
        removeStateKeys("issued." + first.serialNumber());

        IssuedCertificate rebuilt = pki.signingIdentity("ledger-signer");

        assertThat(rebuilt.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(pki.status(first.serialNumber())).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    @DisplayName("身份被吊销后 signingIdentity 应抛 PkiException，不静默恢复")
    void signingIdentity_shouldFailWhenRevoked() {
        IssuedCertificate issued = pki.signingIdentity("revoked-signer");
        pki.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThatThrownBy(() -> pki.signingIdentity("revoked-signer"))
                .isInstanceOf(PkiException.class)
                .hasMessageContaining("rotateSigningIdentity");
    }

    @Test
    @DisplayName("rotateSigningIdentity 应吊销旧身份、换钥并返回新身份")
    void rotateSigningIdentity_shouldReplaceKeyAndRevokeOld() {
        IssuedCertificate old = pki.signingIdentity("rotate-signer");

        IssuedCertificate rotated =
                pki.rotateSigningIdentity("rotate-signer", RevocationReason.SUPERSEDED);

        assertThat(rotated.serialNumber()).isNotEqualTo(old.serialNumber());
        assertThat(rotated.certificate().getPublicKey())
                .isNotEqualTo(old.certificate().getPublicKey());
        assertThat(pki.status(old.serialNumber())).isEqualTo(CertificateStatus.REVOKED);
        assertThat(pki.status(rotated.serialNumber())).isEqualTo(CertificateStatus.VALID);
        assertThat(pki.signingIdentity("rotate-signer").serialNumber())
                .isEqualTo(rotated.serialNumber());
    }

    @Test
    @DisplayName("rotateSigningIdentity 对未使用的 alias 等价于首次签发")
    void rotateSigningIdentity_withoutExisting_shouldCreate() {
        IssuedCertificate rotated =
                pki.rotateSigningIdentity("fresh-signer", RevocationReason.UNSPECIFIED);

        assertThat(rotated.serialNumber()).isNotBlank();
        assertThat(pki.status(rotated.serialNumber())).isEqualTo(CertificateStatus.VALID);
        assertThat(pki.signingIdentity("fresh-signer").serialNumber())
                .isEqualTo(rotated.serialNumber());
    }

    private void markLedgerExpired(String serial) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        }
        props.setProperty("issued." + serial, "0");
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "test");
        }
    }

    private void removeStateKeys(String... keys) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
        }
        for (String key : keys) {
            props.remove(key);
        }
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "test");
        }
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static Path createCaP12(Path dir) throws Exception {
        KeyPair kp = generateKeyPair();

        X500Name dn = new X500Name("C=CN, O=Byteforce, CN=SeedJ Test Root CA");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        dn,
                        BigInteger.valueOf(System.nanoTime()),
                        Date.from(now),
                        Date.from(now.plus(Duration.ofDays(3650))),
                        dn,
                        kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        X509Certificate ca =
                new JcaX509CertificateConverter().getCertificate(builder.build(signer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(CA_ALIAS, kp.getPrivate(), PASSWORD, new Certificate[] {ca});

        Path p12 = dir.resolve("pki-test-ca.p12");
        try (OutputStream out = Files.newOutputStream(p12)) {
            ks.store(out, PASSWORD);
        }
        return p12;
    }
}
