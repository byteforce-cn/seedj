package cn.byteforce.seedj.provider.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.observability.HealthStatus;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PKI 契约测试套件。新 provider 只需 {@code extends} 本类并实现 {@link #newProvider()}。
 *
 * <p>覆盖契约承诺的语义：签发/读取、signingIdentity 幂等、吊销不复活、rotate 换钥、CRL 可解析、
 * issuer 查询、TLS 配置描述与关闭语义。provider 特有的能力（托管密钥、CSR、Transit 签名）由各自的
 * 适配器测试覆盖。
 */
public abstract class PkiProviderContractTest {

    protected PkiProvider provider;

    /** 每次调用必须返回一个全新的、状态隔离的 provider（独立 CA、独立状态文件或独立命名空间）。 */
    protected abstract PkiProvider newProvider();

    /** issue 使用的 role；provider 没有 role 概念时可忽略。 */
    protected String role() {
        return "tck";
    }

    @BeforeEach
    void createProvider() {
        provider = newProvider();
    }

    @AfterEach
    void closeProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @Test
    @DisplayName("issue 返回可读回的证书，serial 与证书内嵌值一致")
    void issue() {
        IssuedCertificate issued =
                provider.issue(
                        role(),
                        CertificateRequest.server(
                                "CN=tck-svc", List.of("tck-svc.local"), List.of()));

        assertThat(issued.certificate()).isNotNull();
        assertThat(issued.chain()).isNotEmpty();
        assertThat(issued.serialNumber()).isEqualTo(issued.certificate().getSerialNumber());
        assertThat(issued.notAfter()).isAfter(issued.notBefore());
        assertThat(provider.readCertificate(issued.serialNumber()))
                .get()
                .extracting(X509Certificate::getSerialNumber)
                .isEqualTo(issued.serialNumber());
    }

    @Test
    @DisplayName("signingIdentity 在证书有效期内幂等：serial 与公钥都不变")
    void signingIdentityIsIdempotent() {
        IssuedCertificate first = provider.signingIdentity("iam");
        IssuedCertificate second = provider.signingIdentity("iam");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
    }

    @Test
    @DisplayName("吊销后 signingIdentity 抛 CertificateRevokedException，不静默恢复")
    void revokedIdentityIsNotResurrected() {
        IssuedCertificate issued = provider.signingIdentity("iam");
        provider.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThat(provider.readCertificate(issued.serialNumber())).isPresent();
        assertThat(revokedSerials()).contains(issued.serialNumber());
        assertThatThrownBy(() -> provider.signingIdentity("iam"))
                .isInstanceOf(CertificateRevokedException.class)
                .extracting(e -> ((CertificateRevokedException) e).serialNumber())
                .isEqualTo(issued.serialNumber());
    }

    @Test
    @DisplayName("rotate 换钥：新 serial，旧 serial 永久进 CRL")
    void rotationRetiresOldIdentity() {
        IssuedCertificate before = provider.signingIdentity("iam");
        IssuedCertificate after =
                provider.rotateSigningIdentity("iam", RevocationReason.SUPERSEDED);

        assertThat(after.serialNumber()).isNotEqualTo(before.serialNumber());
        assertThat(after.certificate().getPublicKey())
                .isNotEqualTo(before.certificate().getPublicKey());
        assertThat(revokedSerials()).contains(before.serialNumber());
        assertThat(provider.signingIdentity("iam").serialNumber()).isEqualTo(after.serialNumber());
    }

    @Test
    @DisplayName("rotate 一个不存在的 alias 等价于 signingIdentity")
    void rotationWithoutExistingIdentity() {
        IssuedCertificate issued =
                provider.rotateSigningIdentity("fresh", RevocationReason.SUPERSEDED);

        assertThat(issued.serialNumber()).isNotNull();
        assertThat(provider.readCertificate(issued.serialNumber())).isPresent();
    }

    @Test
    @DisplayName("未知 serial 返回 Optional.empty，不抛异常")
    void unknownSerial() {
        assertThat(provider.readCertificate(new BigInteger("deadbeef", 16))).isEmpty();
    }

    @Test
    @DisplayName("issuerCertificate / caChain 指向同一张自签 CA")
    void issuer() {
        X509Certificate ca = provider.issuerCertificate("default");

        assertThat(ca.getSubjectX500Principal()).isEqualTo(ca.getIssuerX500Principal());
        assertThat(ca.getBasicConstraints()).isGreaterThanOrEqualTo(0);
        assertThat(provider.caChain("default")).isNotEmpty().first().isEqualTo(ca);
    }

    @Test
    @DisplayName("sslContext(null) 是只信任 CA 的匿名客户端")
    void trustOnlySslContext() {
        SSLContext context = provider.sslContext(null);

        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("sslConfig 描述证书链、密钥归属、客户端认证策略与信任根")
    void sslConfig() {
        IssuedCertificate identity = provider.signingIdentity("tck-tls");
        SslConfig config = provider.sslConfig(identity, ClientAuth.REQUIRED);

        assertThat(config.certificateChain()).isNotEmpty();
        assertThat(config.keyMaterial()).isNotNull();
        assertThat(config.clientAuth()).isEqualTo(ClientAuth.REQUIRED);
        assertThat(config.trustAnchors()).isNotEmpty();
        assertThat(config.delegatingSigning())
                .isEqualTo(config.keyMaterial() instanceof KeyMaterial.CoordManaged);
    }

    @Test
    @DisplayName("info 点名 provider 与 issuer")
    void info() {
        PkiInfo info = provider.info();

        assertThat(info.provider()).isNotBlank();
        assertThat(info.issuerRef()).isNotBlank();
        assertThat(info.issuerSubject()).isNotBlank();
        assertThat(info.issuerNotAfter()).isNotNull();
    }

    @Test
    @DisplayName("close 幂等；关闭后方法与 health 都有明确语义")
    void closeSemantics() {
        provider.close();
        provider.close();

        assertThat(provider.health()).isInstanceOf(HealthStatus.Unknown.class);
        assertThatThrownBy(() -> provider.signingIdentity("iam"))
                .isInstanceOf(PkiClosedException.class);
        assertThatThrownBy(() -> provider.getCrl()).isInstanceOf(PkiClosedException.class);
    }

    private List<BigInteger> revokedSerials() {
        return parseCrl(provider.getCrl()).getRevokedCertificates().stream()
                .map(cert -> cert.getSerialNumber())
                .toList();
    }

    private static X509CRL parseCrl(byte[] crl) {
        try {
            return (X509CRL)
                    CertificateFactory.getInstance("X.509")
                            .generateCRL(new ByteArrayInputStream(crl));
        } catch (Exception e) {
            throw new AssertionError("CRL 必须是可解析的 DER/PEM", e);
        }
    }
}
