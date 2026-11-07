package cn.byteforce.seedj.pki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>覆盖 SPI 承诺的语义：signingIdentity 幂等、吊销不复活、rotate 换钥。
 */
public abstract class PkiProviderContractTest {

    protected PkiProvider provider;

    /** 每次调用必须返回一个全新的、状态隔离的 provider（独立 CA 或独立状态文件）。 */
    protected abstract PkiProvider newProvider();

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
    @DisplayName("issue 返回证书链与序列号，有效期有序")
    void issue() {
        PkiProvider.IssuedCertificate issued =
                provider.issue(
                        PkiProvider.CertificateRequest.server(
                                "CN=svc", List.of("svc.local"), List.of()));

        assertThat(issued.certificate()).isNotNull();
        assertThat(issued.privateKey()).isNotNull();
        assertThat(issued.serialNumber()).isNotBlank();
        assertThat(issued.notAfter()).isAfter(issued.notBefore());
        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.VALID);
    }

    @Test
    @DisplayName("signingIdentity 对同一 alias 幂等：serial 与公钥都不变")
    void signingIdentityIsIdempotent() {
        PkiProvider.IssuedCertificate first = provider.signingIdentity("iam");
        PkiProvider.IssuedCertificate second = provider.signingIdentity("iam");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(second.certificate().getPublicKey())
                .isEqualTo(first.certificate().getPublicKey());
    }

    @Test
    @DisplayName("吊销后 signingIdentity 抛异常，不静默恢复")
    void revokedIdentityIsNotResurrected() {
        PkiProvider.IssuedCertificate issued = provider.signingIdentity("iam");
        provider.revoke(issued.serialNumber(), PkiProvider.RevocationReason.KEY_COMPROMISE);

        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThatThrownBy(() -> provider.signingIdentity("iam")).isInstanceOf(PkiException.class);
    }

    @Test
    @DisplayName("rotate 换钥：新 serial，旧 serial 永久 REVOKED")
    void rotationRetiresOldIdentity() {
        PkiProvider.IssuedCertificate before = provider.signingIdentity("iam");
        PkiProvider.IssuedCertificate after =
                provider.rotateSigningIdentity("iam", PkiProvider.RevocationReason.SUPERSEDED);

        assertThat(after.serialNumber()).isNotEqualTo(before.serialNumber());
        assertThat(after.certificate().getPublicKey())
                .isNotEqualTo(before.certificate().getPublicKey());
        assertThat(provider.status(before.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThat(provider.signingIdentity("iam").serialNumber()).isEqualTo(after.serialNumber());
    }

    @Test
    @DisplayName("rotate 一个不存在的 alias 等价于 signingIdentity，不产生吊销记录")
    void rotationWithoutExistingIdentity() {
        PkiProvider.IssuedCertificate issued =
                provider.rotateSigningIdentity("fresh", PkiProvider.RevocationReason.SUPERSEDED);

        assertThat(issued.serialNumber()).isNotBlank();
        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.VALID);
    }

    @Test
    @DisplayName("未知序列号状态为 UNKNOWN")
    void unknownSerial() {
        assertThat(provider.status("does-not-exist"))
                .isEqualTo(PkiProvider.CertificateStatus.UNKNOWN);
    }

    @Test
    @DisplayName("CA 证书是自签 CA")
    void caCertificate() {
        X509Certificate ca = provider.caCertificate();

        assertThat(ca.getSubjectX500Principal()).isEqualTo(ca.getIssuerX500Principal());
        assertThat(ca.getBasicConstraints()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("sslContext 同时提供身份与信任 CA")
    void sslContext() {
        PkiProvider.IssuedCertificate identity = provider.signingIdentity("svc");
        SSLContext context = provider.sslContext(identity);

        assertThat(context).isNotNull();
        assertThat(provider.trustContext()).isNotNull();
    }

    @Test
    @DisplayName("close 幂等")
    void closeIsIdempotent() {
        provider.close();
        provider.close();
    }
}
