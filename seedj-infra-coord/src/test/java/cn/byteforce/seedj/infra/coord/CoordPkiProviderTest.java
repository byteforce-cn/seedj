package cn.byteforce.seedj.infra.coord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakePkiClient;
import cn.byteforce.seedj.pki.PkiErrorCode;
import cn.byteforce.seedj.pki.PkiException;
import cn.byteforce.seedj.pki.PkiProvider;
import cn.byteforce.seedj.pki.PkiProvider.RevocationReason;
import java.net.InetAddress;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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
    @DisplayName("DNS 名归一化：非法字符替换，空值兜底，超长截断")
    void dnsNameSanitization() {
        assertThat(CoordPkiProvider.dnsName("seedj:iam")).isEqualTo("seedj-iam");
        assertThat(CoordPkiProvider.dnsName("IAM")).isEqualTo("iam");
        assertThat(CoordPkiProvider.dnsName("---")).isEqualTo("seedj-identity");
        assertThat(CoordPkiProvider.dnsName("svc.")).isEqualTo("svc");
        assertThat(CoordPkiProvider.dnsName("A".repeat(80))).hasSize(63);
    }

    @Test
    @DisplayName("issue 按 DN 里的 CN 签发，显式与派生 SAN 都记账")
    void issueUsesCnFromSubjectDn() {
        PkiProvider.IssuedCertificate explicit =
                provider.issue(
                        new PkiProvider.CertificateRequest(
                                "CN=svc-issue,O=byteforce",
                                List.of("svc-issue.local"),
                                List.of("127.0.0.1"),
                                Duration.ofDays(2),
                                null,
                                PkiProvider.CertificateUsage.SERVER));
        PkiProvider.IssuedCertificate derived =
                provider.issue(
                        new PkiProvider.CertificateRequest(
                                "svc-derived",
                                List.of(),
                                List.of(),
                                Duration.ofHours(6),
                                null,
                                PkiProvider.CertificateUsage.CLIENT));

        assertThat(pki.currentSerial("test:svc-issue")).isEqualTo(explicit.serialNumber());
        assertThat(pki.currentSerial("test:svc-derived")).isEqualTo(derived.serialNumber());
        assertThat(explicit.privateKey()).isNotNull();
        assertThat(explicit.notAfter()).isAfter(explicit.notBefore());
        assertThat(provider.status(explicit.serialNumber()))
                .isEqualTo(PkiProvider.CertificateStatus.VALID);
    }

    @Test
    @DisplayName("issue 校验 subject 与 validity：非法输入统一 INVALID_REQUEST")
    void issueRejectsInvalidRequests() {
        assertInvalidRequest(() -> provider.issue(PkiProvider.CertificateRequest.of("   ")));
        assertInvalidRequest(() -> provider.issue(PkiProvider.CertificateRequest.of("OU=no-cn")));
        assertInvalidRequest(
                () -> provider.issue(PkiProvider.CertificateRequest.of("CN=\"unclosed")));
        assertInvalidRequest(
                () ->
                        provider.issue(
                                new PkiProvider.CertificateRequest(
                                        "CN=svc",
                                        List.of(),
                                        List.of(),
                                        Duration.ZERO,
                                        null,
                                        PkiProvider.CertificateUsage.BOTH)));
    }

    @Test
    @DisplayName("alias 必须匹配 [A-Za-z0-9][A-Za-z0-9._-]*")
    void illegalAliasIsRejected() {
        assertInvalidRequest(() -> provider.signingIdentity(null));
        assertInvalidRequest(() -> provider.signingIdentity(""));
        assertInvalidRequest(() -> provider.signingIdentity("bad alias"));
        assertInvalidRequest(() -> provider.signingIdentity("a/b"));
        assertInvalidRequest(
                () -> provider.rotateSigningIdentity("-leading", RevocationReason.SUPERSEDED));
    }

    @Test
    @DisplayName("吊销未知 serial：写占位记录，status 报 REVOKED")
    void revokeUnknownSerialWritesPlaceholder() {
        provider.revoke("not-issued", RevocationReason.CESSATION);

        assertThat(provider.status("not-issued")).isEqualTo(PkiProvider.CertificateStatus.REVOKED);
        assertThat(backendRead("not-issued")).contains("CESSATION");
    }

    @Test
    @DisplayName("CA 证书解析一次后缓存，不重复回读")
    void caCertificateIsCached() {
        X509Certificate first = provider.caCertificate();
        X509Certificate second = provider.caCertificate();

        assertThat(second).isSameAs(first);
        assertThat(first.getSubjectX500Principal().getName()).contains("seedj-test");
    }

    @Test
    @DisplayName("SSLContext 携带身份并信任 CA：本机双向 TLS 握手成功")
    void sslContextCarriesIdentityAndTrust() throws Exception {
        PkiProvider.IssuedCertificate issued = provider.signingIdentity("tls");
        // toIssued 总是补全 chain；这里显式传空 chain，钉住 sslContext 的兜底分支。
        PkiProvider.IssuedCertificate withoutChain =
                new PkiProvider.IssuedCertificate(
                        issued.certificate(),
                        List.of(),
                        issued.privateKey(),
                        issued.serialNumber(),
                        issued.notBefore(),
                        issued.notAfter());

        SSLContext serverContext = provider.sslContext(issued);
        SSLContext clientContext = provider.sslContext(withoutChain);
        assertThat(provider.sslContext(null)).isNotNull();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SSLServerSocket server =
                (SSLServerSocket)
                        serverContext
                                .getServerSocketFactory()
                                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setNeedClientAuth(true);
            server.setSoTimeout(15_000);
            Future<X509Certificate> peer =
                    executor.submit(
                            () -> {
                                try (SSLSocket socket = (SSLSocket) server.accept()) {
                                    socket.setSoTimeout(15_000);
                                    socket.startHandshake();
                                    return (X509Certificate)
                                            socket.getSession().getPeerCertificates()[0];
                                }
                            });

            try (SSLSocket client =
                    (SSLSocket)
                            clientContext
                                    .getSocketFactory()
                                    .createSocket(
                                            InetAddress.getLoopbackAddress(),
                                            server.getLocalPort())) {
                client.setSoTimeout(15_000);
                client.startHandshake();
                assertThat(client.getSession().getCipherSuite()).isNotBlank();
            }

            assertThat(peer.get(20, TimeUnit.SECONDS).getSubjectX500Principal().getName())
                    .contains("seedj-test");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertInvalidRequest(Executable call) {
        assertThatThrownBy(call::execute)
                .isInstanceOf(PkiException.class)
                .extracting(e -> ((PkiException) e).getErrorCode())
                .isEqualTo(PkiErrorCode.INVALID_REQUEST);
    }

    private String backendRead(String serial) {
        return backend.read("seedj:test:coord:pki:serial:" + serial);
    }
}
