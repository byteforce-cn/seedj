package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.contracts.transit.v1.TransitSignRequest;
import cn.byteforce.coord.contracts.transit.v1.TransitVerifyRequest;
import cn.byteforce.coord.sdk.pki.PkiCertInfo;
import cn.byteforce.coord.sdk.pki.PkiCertStatus;
import cn.byteforce.coord.sdk.pki.PkiCertSummary;
import cn.byteforce.coord.sdk.pki.PkiCertUsage;
import cn.byteforce.coord.sdk.pki.PkiClient;
import cn.byteforce.coord.sdk.pki.PkiCrlFormat;
import cn.byteforce.coord.sdk.pki.PkiRevocationReason;
import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.seedj.observability.HealthStatus;
import cn.byteforce.seedj.provider.pki.CertificateRequest;
import cn.byteforce.seedj.provider.pki.CertificateRevokedException;
import cn.byteforce.seedj.provider.pki.CertificateUsage;
import cn.byteforce.seedj.provider.pki.ClientAuth;
import cn.byteforce.seedj.provider.pki.IssuedCertificate;
import cn.byteforce.seedj.provider.pki.KeyMaterial;
import cn.byteforce.seedj.provider.pki.PkiClosedException;
import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import cn.byteforce.seedj.provider.pki.PkiInfo;
import cn.byteforce.seedj.provider.pki.PkiProvider;
import cn.byteforce.seedj.provider.pki.RevocationReason;
import cn.byteforce.seedj.provider.pki.SslConfig;
import cn.byteforce.seedj.provider.pki.TidyRequest;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * coord 0.4.4 PKI + Transit 适配器。
 *
 * <p>能力映射：
 *
 * <ul>
 *   <li>{@code issue} / {@code signCsr} / {@code revoke} / {@code getCrl} / {@code rotateCrl} /
 *       {@code tidy} / {@code readCertificate} 直连 {@code coord.pki.v1}；
 *   <li>{@code sign} / {@code verify} 直连 {@code coord.transit.v1}；
 *   <li>alias → 身份的稳定映射是 seedj 在 coord KV 上补的层（{@link CoordPkiState}），不是 coord
 *       原生概念。
 * </ul>
 *
 * <p>已知偏差（见 {@code docs/guide/known-limitations.md}）：
 *
 * <ul>
 *   <li>coord 没有 role/issuer：单 CA。{@code roleName} 降级为 CN 命名空间前缀
 *       {@code <role>:<cn>}；{@code signCsr} 的 CN 来自 CSR，role 只做格式校验。
 *   <li>托管私钥不可导出：{@link #sslContext} 只接受 {@link KeyMaterial.External}；托管身份用
 *       {@link #sslConfig} 自行委托 {@link #sign}。
 *   <li>签发 profile 由 coord 的 usage 决定；自定义 DN 只保留 CN。
 * </ul>
 */
final class CoordPkiProvider implements PkiProvider {

    private final PkiClient pki;

    private final TransitClient transit;

    private final CoordPkiState state;

    private final CoordEndpoint endpoint;

    private final String identityPrefix;

    private final Duration identityTtl;

    private final Clock clock;

    private final CoordPkiCalls calls;

    private volatile X509Certificate ca;

    private volatile boolean closed;

    CoordPkiProvider(
            PkiClient pki,
            TransitClient transit,
            CoordPkiState state,
            CoordEndpoint endpoint,
            String identityPrefix,
            Duration identityTtl,
            Clock clock) {
        this.pki = Objects.requireNonNull(pki, "pki");
        this.transit = Objects.requireNonNull(transit, "transit");
        this.state = Objects.requireNonNull(state, "state");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.calls = new CoordPkiCalls(endpoint);
        if (identityPrefix == null || !CoordPkiNames.NAME.matcher(identityPrefix).matches()) {
            throw new IllegalArgumentException(
                    "coord pki identity-prefix must match " + CoordPkiNames.NAME.pattern());
        }
        this.identityPrefix = identityPrefix;
        if (identityTtl == null || identityTtl.isZero() || identityTtl.isNegative()) {
            throw new IllegalArgumentException("coord pki identity-ttl must be positive");
        }
        this.identityTtl = identityTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ==================== 签发 ====================

    @Override
    public IssuedCertificate issue(String roleName, CertificateRequest request) {
        ensureOpen();
        CoordPkiNames.requireName(roleName, "roleName");
        Objects.requireNonNull(request, "request");
        String cn = roleName + ":" + CoordPkiNames.commonName(request.requireSubject());
        long ttl = ttlSeconds(request.validity());
        List<String> dns = request.dnsNames().isEmpty() ? List.of(CoordPkiNames.dnsName(cn)) : request.dnsNames();
        PkiCertInfo info =
                call(
                        "issue " + cn,
                        () ->
                                pki.issueCert(
                                        cn,
                                        ttl,
                                        dns,
                                        request.ipAddresses(),
                                        usage(request.usage())));
        return issued(info);
    }

    @Override
    public IssuedCertificate signCsr(
            String roleName, byte[] csrDer, CertificateRequest request) {
        ensureOpen();
        CoordPkiNames.requireName(roleName, "roleName");
        if (csrDer == null || csrDer.length == 0) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "csrDer 不能为空");
        }
        CertificateRequest effective =
                request == null
                        ? CertificateRequest.csr(null, CertificateUsage.BOTH)
                        : request;
        PkiCertInfo info =
                call(
                        "sign csr in role " + roleName,
                        () ->
                                pki.signCsr(
                                        csrDer,
                                        ttlSeconds(effective.validity()),
                                        usage(effective.usage())));
        return issued(info);
    }

    // ==================== 签名 / 验签 ====================

    @Override
    public byte[] sign(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data) {
        ensureOpen();
        CoordPkiNames.requireName(keyName, "keyName");
        if (data == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "data 不能为空");
        }
        TransitSignRequest request =
                TransitSignRequest.newBuilder()
                        .setName(keyName)
                        .setInput(ByteString.copyFrom(data))
                        .setPrehashed(prehashed)
                        .setHashAlgorithm(CoordPkiNames.emptyIfNull(hashAlgorithm))
                        .setSignatureAlgorithm(CoordPkiNames.emptyIfNull(signatureAlgorithm))
                        .build();
        return call("sign with " + keyName, () -> transit.sign(request).getSignature().toByteArray());
    }

    @Override
    public boolean verify(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data,
            byte[] signature) {
        ensureOpen();
        CoordPkiNames.requireName(keyName, "keyName");
        if (data == null || signature == null || signature.length == 0) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "data 与 signature 不能为空");
        }
        TransitVerifyRequest request =
                TransitVerifyRequest.newBuilder()
                        .setName(keyName)
                        .setInput(ByteString.copyFrom(data))
                        .setSignature(ByteString.copyFrom(signature))
                        .setPrehashed(prehashed)
                        .setHashAlgorithm(CoordPkiNames.emptyIfNull(hashAlgorithm))
                        .setSignatureAlgorithm(CoordPkiNames.emptyIfNull(signatureAlgorithm))
                        .build();
        return call("verify with " + keyName, () -> transit.verify(request));
    }

    // ==================== 吊销与 CRL ====================

    @Override
    public void revoke(BigInteger serialNumber, RevocationReason reason) {
        ensureOpen();
        String serial = CoordPkiNames.requireSerial(serialNumber);
        RevocationReason effective = reason == null ? RevocationReason.UNSPECIFIED : reason;
        call(
                "revoke " + serial,
                () -> pki.revokeCert(serial, PkiRevocationReason.valueOf(effective.name())));
    }

    @Override
    public byte[] getCrl() {
        ensureOpen();
        return call("read CRL", () -> pki.getCrl(PkiCrlFormat.DER).crl()).clone();
    }

    @Override
    public void rotateCrl() {
        ensureOpen();
        call("rotate CRL", () -> pki.rotateCrl(PkiCrlFormat.DER));
    }

    @Override
    public void tidy(TidyRequest request) {
        ensureOpen();
        long retentionSeconds =
                request == null ? 0L : request.retentionSeconds(0L);
        call("tidy PKI records", () -> pki.tidy(retentionSeconds));
    }

    // ==================== 证书 / CA 查询 ====================

    @Override
    public Optional<X509Certificate> readCertificate(BigInteger serialNumber) {
        ensureOpen();
        String serial = CoordPkiNames.requireSerial(serialNumber);
        try {
            PkiCertSummary summary =
                    call("read certificate " + serial, () -> pki.getCertBySerial(serial));
            return Optional.of(CoordPkiCertificates.certificate(summary.certPem()));
        } catch (PkiException e) {
            if (e.getErrorCode() == PkiErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public X509Certificate issuerCertificate(String issuerRef) {
        ensureOpen();
        CoordPkiNames.requireDefaultIssuer(issuerRef);
        return caCertificate();
    }

    @Override
    public List<X509Certificate> caChain(String issuerRef) {
        ensureOpen();
        CoordPkiNames.requireDefaultIssuer(issuerRef);
        return List.of(caCertificate());
    }

    // ==================== 身份映射 ====================

    @Override
    public IssuedCertificate signingIdentity(String alias) {
        ensureOpen();
        CoordPkiNames.requireName(alias, "alias");
        String cn = cn(alias);
        Optional<CoordPkiState.AliasRecord> current = state.readAlias(cn);
        if (current.isPresent()) {
            CoordPkiState.AliasRecord record = current.get();
            Optional<PkiCertStatus> status = status(record.currentSerial());
            if (status.isPresent() && status.get().revoked()) {
                throw new CertificateRevokedException(alias, CoordPkiNames.parseSerial(record.currentSerial()));
            }
            if (status.isPresent() && !"expired".equalsIgnoreCase(status.get().status())) {
                // active 或 retired：CN 的当前 active 证书就是新身份。retired 说明轮换已完成，
                // 以 coord 的 active 为准并把 alias 挪过去。
                PkiCertInfo active =
                        call(
                                "read signing identity " + alias,
                                () -> pki.getCertByCN(cn));
                if (!active.serial().equals(record.currentSerial())) {
                    state.replaceAlias(
                            cn,
                            record.currentSerial(),
                            new CoordPkiState.AliasRecord(
                                    alias, cn, active.serial(), ttlSeconds(null)));
                }
                return issued(active);
            }
        }

        PkiCertInfo info =
                call("issue signing identity " + alias, () -> issue(cn));
        CoordPkiState.AliasRecord next =
                new CoordPkiState.AliasRecord(alias, cn, info.serial(), ttlSeconds(null));
        if (current.isEmpty()) {
            if (!state.replaceAlias(cn, null, next)) {
                return reconcile(alias, cn, info);
            }
        } else if (!current.get().currentSerial().equals(info.serial())
                && !state.replaceAlias(cn, current.get().currentSerial(), next)) {
            return reconcile(alias, cn, info);
        }
        return issued(info);
    }

    @Override
    public IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason) {
        ensureOpen();
        CoordPkiNames.requireName(alias, "alias");
        if (reason == null) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "reason 不能为空");
        }
        String cn = cn(alias);
        Optional<CoordPkiState.AliasRecord> current = state.readAlias(cn);
        if (current.isEmpty()) {
            return signingIdentity(alias);
        }
        String oldSerial = current.get().currentSerial();
        // rotateCert 不是幂等的：只调用一次，失败由调用方决定是否重试。
        PkiCertInfo rotated =
                call(
                        "rotate signing identity " + alias,
                        false,
                        () ->
                                pki.rotateCert(
                                        cn,
                                        ttlSeconds(null),
                                        List.of(CoordPkiNames.dnsName(cn)),
                                        List.of(),
                                        null));
        boolean moved =
                state.replaceAlias(
                        cn,
                        oldSerial,
                        new CoordPkiState.AliasRecord(
                                alias, cn, rotated.serial(), ttlSeconds(null)));
        if (!moved) {
            Optional<CoordPkiState.AliasRecord> winner = state.readAlias(cn);
            if (winner.isEmpty()
                    || !winner.get().currentSerial().equals(rotated.serial())) {
                // 另一个实例先完成了轮换：以 coord 的 active 为准，返回可用的新身份。
                rotated = call("read signing identity " + alias, () -> pki.getCertByCN(cn));
            }
        }
        // 旧 serial 永久 REVOKED；重复吊销幂等。
        call(
                "revoke rotated serial " + oldSerial,
                () -> pki.revokeCert(oldSerial, PkiRevocationReason.valueOf(reason.name())));
        return issued(rotated);
    }

    // ==================== TLS ====================

    @Override
    public SSLContext sslContext(IssuedCertificate identity) {
        ensureOpen();
        X509Certificate authority = caCertificate();
        if (identity == null) {
            return CoordPkiCertificates.sslContext(null, null, null, authority);
        }
        if (!(identity.keyMaterial() instanceof KeyMaterial.External external)) {
            throw new IllegalStateException(
                    "coord-managed keys cannot build a standard SSLContext; use sslConfig and delegate engineSign to sign()");
        }
        if (!external.attached()) {
            throw new IllegalStateException(
                    "external private key is not attached; call IssuedCertificate.withPrivateKey first");
        }
        List<X509Certificate> chain =
                identity.chain().isEmpty()
                        ? CoordPkiCertificates.chain(identity.certificate(), authority)
                        : identity.chain();
        return CoordPkiCertificates.sslContext(
                identity.certificate(), external.privateKey(), chain, authority);
    }

    @Override
    public SslConfig sslConfig(IssuedCertificate identity, ClientAuth clientAuth) {
        ensureOpen();
        if (identity == null) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "sslConfig requires a non-null identity");
        }
        return new SslConfig(
                identity.chain(),
                identity.keyMaterial(),
                clientAuth == null ? ClientAuth.NONE : clientAuth,
                List.of(caCertificate()));
    }

    // ==================== 生命周期 ====================

    @Override
    public PkiInfo info() {
        ensureOpen();
        X509Certificate authority = caCertificate();
        return new PkiInfo(
                "coord",
                "default",
                authority.getSubjectX500Principal().getName(),
                authority.getNotAfter().toInstant(),
                true);
    }

    @Override
    public HealthStatus health() {
        if (closed) {
            return HealthStatus.unknown("coord pki provider is closed");
        }
        if (ca != null) {
            return HealthStatus.up("coord CA cached from " + endpoint);
        }
        try {
            caCertificate();
            return HealthStatus.up("coord CA " + endpoint);
        } catch (RuntimeException e) {
            return HealthStatus.down("coord PKI unavailable at " + endpoint + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    // ==================== 内部工具 ====================

    private PkiCertInfo issue(String cn) {
        return pki.issueCert(cn, ttlSeconds(null), List.of(CoordPkiNames.dnsName(cn)), List.of());
    }

    /** CAS 竞争失败后以账本为准：另一个实例可能已经写入同一张证书。 */
    private IssuedCertificate reconcile(String alias, String cn, PkiCertInfo mine) {
        Optional<CoordPkiState.AliasRecord> winner = state.readAlias(cn);
        if (winner.isEmpty() || winner.get().currentSerial().equals(mine.serial())) {
            return issued(mine);
        }
        PkiCertInfo current = call("read signing identity " + alias, () -> pki.getCertByCN(cn));
        return issued(current);
    }

    private Optional<PkiCertStatus> status(String serial) {
        try {
            return Optional.of(call("cert status " + serial, () -> pki.getCertStatus(serial)));
        } catch (PkiException e) {
            if (e.getErrorCode() == PkiErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private IssuedCertificate issued(PkiCertInfo info) {
        X509Certificate leaf = CoordPkiCertificates.certificate(info.certPem());
        X509Certificate authority = caCertificate();
        KeyMaterial material =
                info.keyName() == null || info.keyName().isBlank()
                        ? new KeyMaterial.External()
                        : new KeyMaterial.CoordManaged(info.keyName(), info.keyVersion());
        return new IssuedCertificate(
                leaf, CoordPkiCertificates.chain(leaf, authority), material);
    }

    private X509Certificate caCertificate() {
        X509Certificate cached = ca;
        if (cached != null) {
            return cached;
        }
        String pem =
                call(
                        "read CA certificate",
                        () -> {
                            String value = pki.getCaCert();
                            if (value == null || value.isBlank()) {
                                throw new PkiException(
                                        PkiErrorCode.CA_UNAVAILABLE,
                                        "coord CA is not initialized at "
                                                + endpoint
                                                + "; initialize it with `coord pki ca` or set seedj.coord.pki.init-ca=true");
                            }
                            return value;
                        });
        X509Certificate parsed = CoordPkiCertificates.certificate(pem);
        ca = parsed;
        return parsed;
    }

    private String cn(String alias) {
        return identityPrefix + ":" + alias;
    }

    private long ttlSeconds(Duration validity) {
        Duration effective = validity == null ? identityTtl : validity;
        if (effective.isZero() || effective.isNegative()) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "certificate validity must be positive");
        }
        return Math.max(1L, effective.toSeconds());
    }

    private static PkiCertUsage usage(CertificateUsage usage) {
        return switch (usage) {
            case SERVER -> PkiCertUsage.SERVER;
            case CLIENT -> PkiCertUsage.CLIENT;
            case BOTH -> PkiCertUsage.BOTH;
        };
    }

    private <T> T call(String action, Supplier<T> body) {
        return calls.call(action, body);
    }

    private <T> T call(String action, boolean idempotent, Supplier<T> body) {
        return calls.call(action, idempotent, body);
    }

    private void ensureOpen() {
        if (closed) {
            throw new PkiClosedException("coord");
        }
    }
}
