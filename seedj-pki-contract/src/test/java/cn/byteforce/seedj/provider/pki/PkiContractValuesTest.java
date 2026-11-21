package cn.byteforce.seedj.provider.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.observability.HealthStatus;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PkiContractValuesTest {

    @Test
    @DisplayName("CertificateRequest 列表字段归一化为不可变空列表")
    void certificateRequestNormalizesLists() {
        CertificateRequest request = new CertificateRequest("CN=svc", null, null, null, null);

        assertThat(request.dnsNames()).isEmpty();
        assertThat(request.ipAddresses()).isEmpty();
        assertThat(request.usage()).isEqualTo(CertificateUsage.BOTH);
        assertThatThrownBy(
                        () ->
                                new CertificateRequest(
                                        "CN=svc", null, null, Duration.ZERO, CertificateUsage.BOTH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("server/client 快捷构造只设置预期字段")
    void requestFactories() {
        CertificateRequest server =
                CertificateRequest.server("CN=svc", List.of("svc.local"), List.of("127.0.0.1"));
        CertificateRequest client = CertificateRequest.client("CN=client");

        assertThat(server.usage()).isEqualTo(CertificateUsage.SERVER);
        assertThat(server.dnsNames()).containsExactly("svc.local");
        assertThat(server.validity()).isNull();
        assertThat(client.usage()).isEqualTo(CertificateUsage.CLIENT);
        assertThat(CertificateRequest.of("CN=plain").requireSubject()).isEqualTo("CN=plain");
    }

    @Test
    @DisplayName("CSR 请求不带 subject，issue 侧必须显式要求")
    void csrRequestHasNoSubject() {
        CertificateRequest request =
                CertificateRequest.csr(Duration.ofDays(1), CertificateUsage.SERVER);

        assertThatThrownBy(request::requireSubject).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.usage()).isEqualTo(CertificateUsage.SERVER);
    }

    @Test
    @DisplayName("TidyRequest 用 provider 默认值处理 null 保留期")
    void tidyRequestDefaults() {
        assertThat(TidyRequest.defaults().retentionSeconds(86_400)).isEqualTo(86_400);
        assertThat(TidyRequest.retainFor(Duration.ofHours(1)).retentionSeconds(86_400))
                .isEqualTo(3_600);
        assertThatThrownBy(() -> TidyRequest.retainFor(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RevocationReason 与 RFC 5280 数值互转")
    void revocationReasonCodes() {
        assertThat(RevocationReason.KEY_COMPROMISE.rfcCode()).isEqualTo(1);
        assertThat(RevocationReason.fromRfcCode(6))
                .isEqualTo(RevocationReason.CERTIFICATE_HOLD);
        assertThat(RevocationReason.fromRfcCode(99)).isEqualTo(RevocationReason.UNSPECIFIED);
        assertThat(RevocationReason.AA_COMPROMISE.rfcCode()).isEqualTo(10);
    }

    @Test
    @DisplayName("External 允许未绑定私钥；CoordManaged 必须点名 transit key")
    void keyMaterialValidation() {
        KeyMaterial.External empty = new KeyMaterial.External();
        assertThat(empty.attached()).isFalse();
        assertThatThrownBy(empty::requirePrivateKey).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KeyMaterial.CoordManaged(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyMaterial.CoordManaged("k", -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new KeyMaterial.CoordManaged("pki-key").transitKeyVersion()).isZero();
        assertThat(new KeyMaterial.CoordManaged("pki-key", 3).transitKeyVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("IssuedCertificate 补全空链、暴露有效期与 serial，并可绑定外部私钥")
    void issuedCertificate() throws Exception {
        KeyPair keyPair = keyPair();
        X509Certificate certificate = certificate(keyPair);
        IssuedCertificate external =
                new IssuedCertificate(
                        certificate, List.of(), new KeyMaterial.External(keyPair.getPrivate()));

        assertThat(external.chain()).containsExactly(certificate);
        assertThat(external.serialNumber()).isEqualTo(certificate.getSerialNumber());
        assertThat(external.notBefore()).isEqualTo(certificate.getNotBefore().toInstant());
        assertThat(external.notAfter()).isEqualTo(certificate.getNotAfter().toInstant());
        assertThat(external.expired(Instant.now().plusSeconds(60))).isTrue();
        assertThat(external.expired(Instant.now())).isFalse();
        assertThat(external.withPrivateKey(keyPair.getPrivate()).keyMaterial())
                .isInstanceOf(KeyMaterial.External.class);

        IssuedCertificate managed =
                new IssuedCertificate(
                        certificate, List.of(certificate), new KeyMaterial.CoordManaged("key", 1));
        assertThatThrownBy(() -> managed.withPrivateKey(keyPair.getPrivate()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () -> new IssuedCertificate(certificate, List.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SslConfig 复制列表并区分委托签名")
    void sslConfig() throws Exception {
        KeyPair keyPair = keyPair();
        X509Certificate certificate = certificate(keyPair);

        SslConfig external =
                new SslConfig(
                        List.of(certificate),
                        new KeyMaterial.External(keyPair.getPrivate()),
                        ClientAuth.REQUIRED,
                        List.of(certificate));
        SslConfig managed =
                new SslConfig(
                        List.of(certificate),
                        new KeyMaterial.CoordManaged("key", 1),
                        ClientAuth.NONE,
                        List.of());

        assertThat(external.certificateChain()).containsExactly(certificate);
        assertThat(external.trustAnchors()).containsExactly(certificate);
        assertThat(external.delegatingSigning()).isFalse();
        assertThat(managed.delegatingSigning()).isTrue();
        assertThat(managed.clientAuth()).isEqualTo(ClientAuth.NONE);
    }

    @Test
    @DisplayName("PkiInfo 拒绝缺失字段")
    void pkiInfoValidation() {
        PkiInfo info =
                new PkiInfo("local", "default", "CN=ca", Instant.EPOCH, false);
        assertThat(info.managedPrivateKeys()).isFalse();
        assertThatThrownBy(() -> new PkiInfo(null, "default", "CN=ca", Instant.EPOCH, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("HealthStatus：只有 Up 健康，Unknown 不冒充健康")
    void healthStatus() {
        assertThat(HealthStatus.up().healthy()).isTrue();
        assertThat(((HealthStatus.Up) HealthStatus.up("detail")).detail()).isEqualTo("detail");
        assertThat(HealthStatus.down("boom").healthy()).isFalse();
        assertThat(
                        ((HealthStatus.Down)
                                        HealthStatus.down(
                                                "boom", new IllegalStateException()))
                                .cause())
                .isInstanceOf(IllegalStateException.class);
        assertThat(HealthStatus.unknown("not configured").healthy()).isFalse();
    }

    @Test
    @DisplayName("异常携带稳定错误码与 alias/serial")
    void exceptions() {
        CertificateRevokedException revoked =
                new CertificateRevokedException("iam", new BigInteger("ff", 16));

        assertThat(revoked.getErrorCode()).isEqualTo(PkiErrorCode.REVOKED);
        assertThat(revoked.alias()).isEqualTo("iam");
        assertThat(revoked.serialNumber()).isEqualTo(BigInteger.valueOf(255));
        assertThat(revoked).hasMessageContaining("rotateSigningIdentity");
        assertThat(new CertificateRevokedException(null, null).serialNumber()).isNull();
        assertThat(new PkiClosedException("coord"))
                .hasMessageContaining("coord")
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.PROVIDER_CLOSED);
        assertThat(new PkiException("boom").getErrorCode())
                .isEqualTo(PkiErrorCode.INTERNAL_ERROR);
        assertThat(new PkiException(PkiErrorCode.NOT_FOUND, "missing", null).getErrorCode())
                .isEqualTo(PkiErrorCode.NOT_FOUND);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair keyPair) throws Exception {
        X500Name name = new X500Name("CN=test");
        Date now = new Date();
        return new JcaX509CertificateConverter()
                .getCertificate(
                        new JcaX509v3CertificateBuilder(
                                        name,
                                        BigInteger.valueOf(42),
                                        now,
                                        new Date(now.getTime() + 60_000),
                                        name,
                                        keyPair.getPublic())
                                .build(
                                        new JcaContentSignerBuilder("SHA256withRSA")
                                                .build(keyPair.getPrivate())));
    }
}
