package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakePkiClient;
import cn.byteforce.seedj.pki.PkiErrorCode;
import cn.byteforce.seedj.pki.PkiException;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordPkiProviderTest {

    private final FakeCoordBackend backend = new FakeCoordBackend();

    private final FakePkiClient pki = new FakePkiClient();

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);

    private CoordPkiProvider provider;

    @BeforeEach
    void setUp() {
        provider =
                new CoordPkiProvider(
                        pki,
                        new CoordPkiState(
                                new CoordKvStore(backend.kv(), backend.txn()),
                                CoordKeySpace.of("seedj:test", "pki")),
                        new CoordEndpoint("127.0.0.1:19527"),
                        "test",
                        Duration.ofHours(24),
                        clock);
    }

    @Test
    @DisplayName("signingIdentity 幂等：同一 alias 复用同一 serial，CN 带实例前缀")
    void signingIdentityIsIdempotent() {
        PkiProvider.IssuedCertificate first = provider.signingIdentity("iam");
        PkiProvider.IssuedCertificate second = provider.signingIdentity("iam");

        assertThat(second.serialNumber()).isEqualTo(first.serialNumber());
        assertThat(pki.currentSerial("test:iam")).isEqualTo(first.serialNumber());
        assertThat(provider.status(first.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.VALID);
    }

    @Test
    @DisplayName("吊销后 signingIdentity 抛 REVOKED，不静默恢复")
    void revokedIdentityIsNotResurrected() {
        PkiProvider.IssuedCertificate issued = provider.signingIdentity("iam");
        provider.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);

        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> provider.signingIdentity("iam")))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.REVOKED);
    }

    @Test
    @DisplayName("重复吊销幂等，且首个原因不会被覆盖")
    void revokeIsIdempotentAndKeepsFirstReason() {
        PkiProvider.IssuedCertificate issued = provider.signingIdentity("svc");
        provider.revoke(issued.serialNumber(), RevocationReason.KEY_COMPROMISE);
        provider.revoke(issued.serialNumber(), RevocationReason.SUPERSEDED);

        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThat(backendRead(issued.serialNumber())).contains("KEY_COMPROMISE");
    }

    @Test
    @DisplayName("rotate 换 serial，旧 serial 永久 REVOKED")
    void rotateRetiresOldSerial() {
        PkiProvider.IssuedCertificate before = provider.signingIdentity("iam");
        PkiProvider.IssuedCertificate after =
                provider.rotateSigningIdentity("iam", RevocationReason.SUPERSEDED);

        assertThat(after.serialNumber()).isNotEqualTo(before.serialNumber());
        assertThat(provider.status(before.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThat(provider.signingIdentity("iam").serialNumber()).isEqualTo(after.serialNumber());
    }

    @Test
    @DisplayName("rotate 一个不存在的 alias 等价于 signingIdentity")
    void rotateWithoutExistingIdentity() {
        PkiProvider.IssuedCertificate issued =
                provider.rotateSigningIdentity("fresh", RevocationReason.SUPERSEDED);

        assertThat(provider.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.VALID);
    }

    @Test
    @DisplayName("过期证书报 EXPIRED，未知 serial 报 UNKNOWN")
    void statusDistinguishesExpiredAndUnknown() {
        PkiProvider.IssuedCertificate issued = provider.signingIdentity("iam");
        CoordPkiProvider later =
                new CoordPkiProvider(
                        pki,
                        new CoordPkiState(
                                new CoordKvStore(backend.kv(), backend.txn()),
                                CoordKeySpace.of("seedj:test", "pki")),
                        new CoordEndpoint("127.0.0.1:19527"),
                        "test",
                        Duration.ofHours(24),
                        Clock.offset(clock, Duration.ofDays(3650)));

        assertThat(later.status(issued.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.EXPIRED);
        assertThat(later.status("does-not-exist")).isEqualTo(PkiProvider.CertificateStatus.UNKNOWN);
    }

    @Test
    @DisplayName("keyPair 交付被拒绝：coord 自己生成密钥")
    void callerSuppliedKeyPairIsRejected() {
        PkiProvider.CertificateRequest request =
                new PkiProvider.CertificateRequest(
                        "CN=svc",
                        java.util.List.of("svc.local"),
                        java.util.List.of(),
                        Duration.ofDays(1),
                        new java.security.KeyPair(null, null),
                        PkiProvider.CertificateUsage.BOTH);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> provider.issue(request)))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("CA 未初始化时点名初始化方式")
    void missingCaIsNamed() {
        pki.caInitialized(false);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> provider.caCertificate()))
                .isInstanceOf(PkiException.class)
                .hasMessageContaining("seedj.coord.pki.init-ca");
    }

    @Test
    @DisplayName("agent 不可达映射为可重试的 STATE_UNAVAILABLE")
    void unreachableMapsToRetryableError() {
        pki.down(true);

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> provider.signingIdentity("iam")))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.STATE_UNAVAILABLE);
    }

    @Test
    @DisplayName("close 幂等，之后抛 PROVIDER_CLOSED")
    void closeSemantics() {
        provider.close();
        provider.close();

        assertThat(
                        org.assertj.core.api.Assertions.catchThrowable(
                                () -> provider.signingIdentity("iam")))
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.PROVIDER_CLOSED);
    }

    @Test
    @DisplayName("DNS 名归一化：非法字符替换，空值兜底")
    void dnsNameSanitization() {
        assertThat(CoordPkiProvider.dnsName("seedj:iam")).isEqualTo("seedj-iam");
        assertThat(CoordPkiProvider.dnsName("IAM")).isEqualTo("iam");
        assertThat(CoordPkiProvider.dnsName("---")).isEqualTo("seedj-identity");
    }

    private String backendRead(String serial) {
        return backend.read("seedj:test:coord:pki:serial:" + serial);
    }
}
