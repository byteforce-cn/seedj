package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.pki.PkiCertInfo;
import cn.byteforce.coord.sdk.pki.PkiCertSummary;
import cn.byteforce.coord.sdk.pki.PkiClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版 coord PKI：只为适配器的单元测试服务。
 *
 * <p>行为对齐真实 agent 的三条：{@code issueCert} 按 CN get-or-create、续期/轮换换新 serial、CA 未初始化时
 * {@code getCaCert} 返回空。证书与私钥是 {@code src/test/resources/pki} 下的固定 PEM（P-256），
 * 所以 PEM 解析路径也被覆盖。
 */
public final class FakePkiClient implements PkiClient {

    private final Map<String, PkiCertInfo> byCn = new LinkedHashMap<>();

    private final AtomicInteger serials = new AtomicInteger(1000);

    private final String certPem;

    private final String keyPem;

    private boolean caInitialized = true;

    private boolean down;

    public FakePkiClient() {
        this.certPem = resource("/pki/test-cert.pem");
        this.keyPem = resource("/pki/test-key.pem");
    }

    public void down(boolean value) {
        this.down = value;
    }

    public void caInitialized(boolean value) {
        this.caInitialized = value;
    }

    public String currentSerial(String cn) {
        PkiCertInfo info = byCn.get(cn);
        return info == null ? null : info.serial();
    }

    @Override
    public void initCa(String caCommonName) {
        check();
        caInitialized = true;
    }

    @Override
    public PkiCertInfo issueCert(String commonName) {
        return issueCert(commonName, 86_400L);
    }

    @Override
    public PkiCertInfo issueCert(String commonName, long ttlSeconds) {
        return issueCert(commonName, ttlSeconds, List.of(), List.of());
    }

    @Override
    public PkiCertInfo issueCert(
            String commonName, long ttlSeconds, List<String> dnsNames, List<String> ipAddresses) {
        check();
        return byCn.computeIfAbsent(commonName, cn -> newCert(cn, ttlSeconds));
    }

    @Override
    public PkiCertInfo renewCert(String serial, long ttlSeconds) {
        check();
        return byCn.values().stream()
                .filter(info -> info.serial().equals(serial))
                .findFirst()
                .map(info -> newCert(info.commonName(), ttlSeconds))
                .orElseThrow(() -> new CoordException(ErrorCode.NOT_FOUND, "no such serial"));
    }

    @Override
    public PkiCertInfo rotateCert(String commonName) {
        return rotateCert(commonName, 86_400L);
    }

    @Override
    public PkiCertInfo rotateCert(String commonName, long ttlSeconds) {
        return rotateCert(commonName, ttlSeconds, List.of(), List.of());
    }

    @Override
    public PkiCertInfo rotateCert(
            String commonName, long ttlSeconds, List<String> dnsNames, List<String> ipAddresses) {
        check();
        PkiCertInfo rotated = newCert(commonName, ttlSeconds);
        byCn.put(commonName, rotated);
        return rotated;
    }

    @Override
    public List<PkiCertSummary> listCerts(String commonName) {
        check();
        PkiCertInfo info = byCn.get(commonName);
        if (info == null) {
            return List.of();
        }
        return List.of(
                new PkiCertSummary(
                        info.commonName(),
                        info.certPem(),
                        info.notBeforeEpochSec(),
                        info.notAfterEpochSec(),
                        info.serial(),
                        info.status(),
                        info.parentSerial()));
    }

    @Override
    public PkiCertInfo getCertByCN(String commonName) {
        check();
        PkiCertInfo info = byCn.get(commonName);
        if (info == null) {
            throw new CoordException(ErrorCode.NOT_FOUND, "no active certificate for CN");
        }
        return info;
    }

    @Override
    public boolean verifyCert(String certPem) {
        check();
        return certPem != null && certPem.contains("BEGIN CERTIFICATE");
    }

    @Override
    public String getCaCert() {
        check();
        return caInitialized ? certPem : "";
    }

    private PkiCertInfo newCert(String commonName, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        return new PkiCertInfo(
                commonName,
                certPem,
                keyPem,
                now - 60,
                now + Math.max(1L, ttlSeconds),
                String.valueOf(serials.incrementAndGet()),
                "active",
                null);
    }

    private void check() {
        if (down) {
            throw new CoordException(ErrorCode.UNAVAILABLE, "agent unreachable");
        }
    }

    private static String resource(String path) {
        try (InputStream in = FakePkiClient.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
