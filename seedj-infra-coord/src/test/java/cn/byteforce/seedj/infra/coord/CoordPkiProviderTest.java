package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.contracts.transit.v1.TransitSignRequest;
import cn.byteforce.coord.contracts.transit.v1.TransitSignResponse;
import cn.byteforce.coord.contracts.transit.v1.TransitVerifyRequest;
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakePkiClient;
import cn.byteforce.seedj.provider.pki.CertificateRequest;
import cn.byteforce.seedj.provider.pki.CertificateUsage;
import cn.byteforce.seedj.provider.pki.IssuedCertificate;
import cn.byteforce.seedj.provider.pki.KeyMaterial;
import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.provider.pki.RevocationReason;
import cn.byteforce.seedj.provider.pki.TidyRequest;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CoordPkiProviderTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();

    private final FakePkiClient pki = new FakePkiClient();

    private final TransitClient transit = mock(TransitClient.class);

    private CoordPkiProvider provider;

    @BeforeEach
    void setUp() {
        provider = newProvider("test");
    }

    @Test
    @DisplayName("issue 以 role 为 CN 前缀，返回 CoordManaged 的 transit key")
    void issueUsesRolePrefixAndManagedKey() {
        IssuedCertificate issued =
                provider.issue(
                        "server",
                        CertificateRequest.server(
                                "CN=orders-api", List.of("orders-api.local"), List.of()));

        assertThat(pki.currentSerial("server:orders-api"))
                .isEqualTo(issued.serialNumber().toString(16));
        assertThat(issued.keyMaterial()).isInstanceOf(KeyMaterial.CoordManaged.class);
        assertThat(((KeyMaterial.CoordManaged) issued.keyMaterial()).transitKeyName())
                .isEqualTo("pki-server-orders-api");
        assertThat(issued.certificate().getSubjectX500Principal().getName())
                .contains("CN=server:orders-api");
    }

    @Test
    @DisplayName("issue 从 DN 中取 CN，并在未传 SAN 时派生 DNS SAN")
    void issueDerivesDnsNameFromCn() throws Exception {
        IssuedCertificate issued =
                provider.issue(
                        "client",
                        new CertificateRequest(
                                "CN=payments,O=Byteforce",
                                List.of(),
                                List.of(),
                                Duration.ofDays(1),
                                CertificateUsage.CLIENT));

        assertThat(issued.certificate().getSubjectAlternativeNames())
                .anySatisfy(
                        entry -> {
                            assertThat(entry.get(0)).isEqualTo(2);
                            assertThat(entry.get(1)).isEqualTo("client-payments");
                        });
    }

    @Test
    @DisplayName("signCsr 返回 External 未绑定钥，withPrivateKey 后可建 SSLContext")
    void signCsrReturnsExternalKey() throws Exception {
        KeyPair keyPair = keyPair();
        PKCS10CertificationRequest csr = csr("CN=csr-svc", keyPair, "csr.local");

        IssuedCertificate issued =
                provider.signCsr(
                        "client",
                        csr.getEncoded(),
                        CertificateRequest.csr(Duration.ofDays(1), CertificateUsage.CLIENT));

        assertThat(issued.keyMaterial()).isInstanceOf(KeyMaterial.External.class);
        assertThat(((KeyMaterial.External) issued.keyMaterial()).attached()).isFalse();
        assertThat(issued.certificate().getSubjectAlternativeNames())
                .anySatisfy(entry -> assertThat(entry.get(1)).isEqualTo("csr.local"));
        assertThat(provider.sslContext(issued.withPrivateKey(keyPair.getPrivate()))).isNotNull();
    }

    @Test
    @DisplayName("sslContext 拒绝 CoordManaged 身份")
    void sslContextRejectsManagedKey() {
        IssuedCertificate issued =
                provider.issue("server", CertificateRequest.server("CN=managed", List.of(), List.of()));

        assertThatThrownBy(() -> provider.sslContext(issued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslConfig");
    }

    @Test
    @DisplayName("sign/verify 原样透传 hash/prehashed/signatureAlgorithm")
    void signVerifyPassThrough() {
        byte[] signature = {1, 2, 3};
        when(transit.sign(any()))
                .thenReturn(
                        TransitSignResponse.newBuilder()
                                .setSignature(ByteString.copyFrom(signature))
                                .build());
        when(transit.verify(any())).thenReturn(true);

        byte[] data = {9, 8, 7};
        assertThat(provider.sign("pki-key", "sha2-256", true, "pkcs1v15", data))
                .containsExactly(signature);

        ArgumentCaptor<TransitSignRequest> sign = ArgumentCaptor.forClass(TransitSignRequest.class);
        verify(transit).sign(sign.capture());
        assertThat(sign.getValue().getName()).isEqualTo("pki-key");
        assertThat(sign.getValue().getPrehashed()).isTrue();
        assertThat(sign.getValue().getHashAlgorithm()).isEqualTo("sha2-256");
        assertThat(sign.getValue().getSignatureAlgorithm()).isEqualTo("pkcs1v15");

        assertThat(provider.verify("pki-key", "sha2-256", true, "pkcs1v15", data, signature))
                .isTrue();
        ArgumentCaptor<TransitVerifyRequest> verify =
                ArgumentCaptor.forClass(TransitVerifyRequest.class);
        verify(transit).verify(verify.capture());
        assertThat(verify.getValue().getSignature().toByteArray()).containsExactly(signature);
    }

    @Test
    @DisplayName("revoke 用证书内嵌 serial 的十六进制，CRL 可解析")
    void revokeAndCrl() {
        IssuedCertificate issued =
                provider.issue("client", CertificateRequest.client("CN=revoke-me"));
        provider.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThat(provider.readCertificate(issued.serialNumber())).isPresent();
        assertThat(provider.getCrl()).isNotEmpty();
        assertThat(provider.readCertificate(new BigInteger("deadbeef", 16))).isEmpty();
    }

    @Test
    @DisplayName("tidy / rotateCrl 直连 coord")
    void tidyAndRotateCrl() {
        provider.tidy(TidyRequest.retainFor(Duration.ofHours(1)));
        provider.rotateCrl();

        assertThat(provider.getCrl()).isNotEmpty();
    }

    @Test
    @DisplayName("issuerRef 只接受单 CA 的 default")
    void issuerRejectsUnknownRef() {
        assertThat(provider.issuerCertificate("default")).isNotNull();
        assertThatThrownBy(() -> provider.issuerCertificate("other"))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("health 在 agent 不可达时返回 down，不抛异常")
    void healthReportsDown() {
        pki.down(true);

        assertThat(provider.health().healthy()).isFalse();
    }

    @Test
    @DisplayName("info 点名 coord 与单 CA，托管私钥为 true")
    void info() {
        assertThat(provider.info().provider()).isEqualTo("coord");
        assertThat(provider.info().issuerRef()).isEqualTo("default");
        assertThat(provider.info().managedPrivateKeys()).isTrue();
    }

    @Test
    @DisplayName("close 幂等，之后抛 PkiClosedException")
    void closeSemantics() {
        provider.close();
        provider.close();

        assertThat(provider.health().healthy()).isFalse();
        assertThatThrownBy(() -> provider.signingIdentity("iam"))
                .isInstanceOf(cn.byteforce.seedj.provider.pki.PkiClosedException.class);
    }

    @Test
    @DisplayName("非法 role / alias 统一 INVALID_REQUEST")
    void invalidNames() {
        assertThatThrownBy(
                        () -> provider.issue("bad role", CertificateRequest.client("CN=x")))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> provider.signingIdentity("bad alias"))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("DNS 名归一化：非法字符替换，空值兜底，超长截断")
    void dnsNameSanitization() {
        assertThat(CoordPkiNames.dnsName("seedj:iam")).isEqualTo("seedj-iam");
        assertThat(CoordPkiNames.dnsName("IAM")).isEqualTo("iam");
        assertThat(CoordPkiNames.dnsName("---")).isEqualTo("seedj-identity");
        assertThat(CoordPkiNames.dnsName("svc.")).isEqualTo("svc");
        assertThat(CoordPkiNames.dnsName("A".repeat(80))).hasSize(63);
    }

    private CoordPkiProvider newProvider(String prefix) {
        return new CoordPkiProvider(
                pki,
                transit,
                new CoordPkiState(
                        new CoordKvStore(backend.kv(), backend.txn()),
                        CoordKeySpace.of("seedj:test", "pki")),
                new CoordEndpoint("127.0.0.1:19527"),
                prefix,
                Duration.ofHours(24),
                Clock.systemUTC());
    }

    private static KeyPair keyPair() throws Exception {
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
                                new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName))
                                        .getEncoded()));
        return new JcaPKCS10CertificationRequestBuilder(
                        new X500Name(subject), keyPair.getPublic())
                .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
                .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                                .build(keyPair.getPrivate()));
    }
}
