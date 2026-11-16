package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.pki.PkiCertInfo;
import cn.byteforce.coord.sdk.pki.PkiClient;
import cn.byteforce.seedj.pki.PkiErrorCode;
import cn.byteforce.seedj.pki.PkiException;
import cn.byteforce.seedj.pki.PkiProvider;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLContext;

/**
 * coord PKI 适配器：coord 负责签发/轮换，吊销与 alias → 身份的稳定映射由 {@link CoordPkiState} 在 coord KV 上补齐。
 *
 * <p>已知偏差（见 {@code docs/guide/known-limitations.md}）：
 *
 * <ul>
 *   <li>coord 没有吊销列表，{@link #revoke} 只对 seedj 自己的 {@code signingIdentity}/{@link #status} 生效：
 *       调用方不会再拿到被吊销的身份，但 TLS 对端不认。
 *   <li>证书 profile 由 coord 固定（ECDSA P-256、KU {@code digitalSignature+keyEncipherment}、EKU {@code
 *       clientAuth+serverAuth}）：{@link CertificateUsage#SERVER}/{@link CertificateUsage#CLIENT}
 *       拿到的是请求的超集，不是精确匹配。
 *   <li>coord 要求 CN 全局唯一且 {@code GetCertByCN} 返回私钥，所以 CN 带实例前缀（{@code <prefix>:<alias>}）； 签出来的
 *       subject 因此与调用方传入的 subject 不完全一致。
 *   <li>{@link CertificateRequest#keyPair} 无法使用：coord 自己生成密钥（「密钥交付持有方」模型）。
 * </ul>
 */
final class CoordPkiProvider implements PkiProvider {

    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private static final Pattern DNS_SAFE = Pattern.compile("[^a-z0-9.-]");

    private static final int RETRY_ATTEMPTS = 3;

    private final PkiClient pki;

    private final CoordPkiState state;

    private final CoordEndpoint endpoint;

    private final String identityPrefix;

    private final Duration identityTtl;

    private final Clock clock;

    private volatile X509Certificate ca;

    private volatile boolean closed;

    CoordPkiProvider(
            PkiClient pki,
            CoordPkiState state,
            CoordEndpoint endpoint,
            String identityPrefix,
            Duration identityTtl,
            Clock clock) {
        this.pki = Objects.requireNonNull(pki, "pki");
        this.state = Objects.requireNonNull(state, "state");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (identityPrefix == null || !ALIAS.matcher(identityPrefix).matches()) {
            throw new IllegalArgumentException(
                    "coord pki identity-prefix must match " + ALIAS.pattern());
        }
        this.identityPrefix = identityPrefix;
        if (identityTtl == null || identityTtl.isZero() || identityTtl.isNegative()) {
            throw new IllegalArgumentException("coord pki identity-ttl must be positive");
        }
        this.identityTtl = identityTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public IssuedCertificate issue(CertificateRequest request) {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        if (request.keyPair() != null) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST,
                    "coord PKI generates key material itself; CertificateRequest.keyPair is not supported");
        }
        String cn = cn(subjectCommonName(request.subject()));
        long ttl = ttlSeconds(request.validity());
        List<String> dns = request.dnsNames().isEmpty() ? List.of(dnsName(cn)) : request.dnsNames();
        PkiCertInfo info =
                call("issue " + cn, () -> pki.issueCert(cn, ttl, dns, request.ipAddresses()));
        record(info, null, ttl);
        return toIssued(info);
    }

    @Override
    public IssuedCertificate signingIdentity(String alias) {
        ensureOpen();
        requireAlias(alias);
        String cn = cn(alias);
        // 账本键用 CN 而不是裸 alias：CN 带实例前缀，同一 agent 上的不同部署/测试互不可见。
        Optional<CoordPkiState.AliasRecord> current = state.readAlias(cn);
        if (current.isPresent() && isRevoked(current.get().currentSerial())) {
            throw new PkiException(
                    PkiErrorCode.REVOKED,
                    "signing identity "
                            + alias
                            + " was revoked (serial "
                            + current.get().currentSerial()
                            + "); call rotateSigningIdentity to replace it");
        }

        PkiCertInfo info = call("signing identity " + alias, () -> issue(cn));
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
        record(info, alias, ttlSeconds(null));
        return toIssued(info);
    }

    @Override
    public IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason) {
        ensureOpen();
        requireAlias(alias);
        // 账本键用 CN 而不是裸 alias：CN 带实例前缀，同一 agent 上的不同部署/测试互不可见。
        String cn = cn(alias);
        Optional<CoordPkiState.AliasRecord> current = state.readAlias(cn);
        if (current.isEmpty()) {
            return signingIdentity(alias);
        }
        long ttl = ttlSeconds(null);
        PkiCertInfo info =
                call(
                        "rotate signing identity " + alias,
                        false,
                        () -> pki.rotateCert(cn, ttl, List.of(dnsName(cn)), List.of()));
        boolean moved =
                state.replaceAlias(
                        cn,
                        current.get().currentSerial(),
                        new CoordPkiState.AliasRecord(alias, cn, info.serial(), ttl));
        if (!moved) {
            // alias 没挪到新身份：不要把旧 serial 吊销掉，否则调用方会失去一切可用身份。
            throw new PkiException(
                    PkiErrorCode.STATE_UNAVAILABLE,
                    "coord rotated the certificate but the alias "
                            + alias
                            + " still points at "
                            + current.get().currentSerial()
                            + "; retry rotateSigningIdentity");
        }
        record(info, alias, ttl);
        // 先把 alias 挪到新身份再吊销旧 serial：轮换失败时不会留下「已吊销但没有替身」的身份。
        state.revoke(current.get().currentSerial(), reason, clock.instant());
        return toIssued(info);
    }

    @Override
    public void revoke(String serialNumber, RevocationReason reason) {
        ensureOpen();
        Objects.requireNonNull(serialNumber, "serialNumber");
        Objects.requireNonNull(reason, "reason");
        if (!state.hasSerial(serialNumber)) {
            state.putSerialIfAbsent(state.unknownSerial(serialNumber, reason, clock.instant()));
            return;
        }
        state.revoke(serialNumber, reason, clock.instant());
    }

    @Override
    public CertificateStatus status(String serialNumber) {
        ensureOpen();
        Optional<CoordPkiState.SerialRecord> record = state.serial(serialNumber);
        if (record.isEmpty()) {
            return CertificateStatus.UNKNOWN;
        }
        CoordPkiState.SerialRecord value = record.get();
        if (value.revoked()) {
            return CertificateStatus.REVOKED;
        }
        if (value.notAfterMs() > 0 && value.notAfterMs() <= clock.millis()) {
            return CertificateStatus.EXPIRED;
        }
        return CertificateStatus.VALID;
    }

    @Override
    public X509Certificate caCertificate() {
        ensureOpen();
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

    @Override
    public SSLContext sslContext(IssuedCertificate identity) {
        ensureOpen();
        X509Certificate authority = caCertificate();
        if (identity == null) {
            return CoordPkiCertificates.sslContext(null, null, null, authority);
        }
        List<X509Certificate> chain =
                identity.chain() == null || identity.chain().isEmpty()
                        ? CoordPkiCertificates.chain(identity.certificate(), authority)
                        : identity.chain();
        return CoordPkiCertificates.sslContext(
                identity.certificate(), identity.privateKey(), chain, authority);
    }

    @Override
    public void close() {
        closed = true;
    }

    private PkiCertInfo issue(String cn) {
        return pki.issueCert(cn, ttlSeconds(null), List.of(dnsName(cn)), List.of());
    }

    /** CAS 竞争失败后以账本为准：另一个实例可能已经写入同一张证书。 */
    private IssuedCertificate reconcile(String alias, String cn, PkiCertInfo mine) {
        Optional<CoordPkiState.AliasRecord> winner = state.readAlias(cn);
        if (winner.isEmpty() || winner.get().currentSerial().equals(mine.serial())) {
            record(mine, alias, ttlSeconds(null));
            return toIssued(mine);
        }
        PkiCertInfo current = call("read signing identity " + alias, () -> pki.getCertByCN(cn));
        record(current, alias, ttlSeconds(null));
        return toIssued(current);
    }

    private void record(PkiCertInfo info, String alias, long ttlSeconds) {
        state.putSerialIfAbsent(
                new CoordPkiState.SerialRecord(
                        info.serial(),
                        alias,
                        info.commonName(),
                        info.notBeforeEpochSec() * 1000L,
                        info.notAfterEpochSec() * 1000L,
                        false,
                        null,
                        0L));
    }

    private boolean isRevoked(String serial) {
        return state.serial(serial).map(CoordPkiState.SerialRecord::revoked).orElse(false);
    }

    private IssuedCertificate toIssued(PkiCertInfo info) {
        X509Certificate leaf = CoordPkiCertificates.certificate(info.certPem());
        PrivateKey key = CoordPkiCertificates.privateKey(info.keyPem(), leaf);
        X509Certificate authority = caCertificate();
        return new IssuedCertificate(
                leaf,
                CoordPkiCertificates.chain(leaf, authority),
                key,
                info.serial(),
                Instant.ofEpochSecond(info.notBeforeEpochSec()),
                Instant.ofEpochSecond(info.notAfterEpochSec()));
    }

    private String cn(String name) {
        return identityPrefix + ":" + name;
    }

    private static String subjectCommonName(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "subject must not be blank");
        }
        if (!subject.contains("=")) {
            return subject;
        }
        try {
            for (Rdn rdn : new LdapName(subject).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    String value = String.valueOf(rdn.getValue());
                    if (value.isBlank()) {
                        break;
                    }
                    return value;
                }
            }
        } catch (Exception e) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "cannot parse subject: " + subject, e);
        }
        throw new PkiException(
                PkiErrorCode.INVALID_REQUEST, "subject has no CN component: " + subject);
    }

    private static void requireAlias(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "illegal signing identity alias: " + alias);
        }
    }

    private long ttlSeconds(Duration validity) {
        Duration effective = validity == null ? identityTtl : validity;
        if (effective.isZero() || effective.isNegative()) {
            throw new PkiException(
                    PkiErrorCode.INVALID_REQUEST, "certificate validity must be positive");
        }
        return Math.max(1L, effective.toSeconds());
    }

    /**
     * coord 在未显式传 SAN 时会把 CN 派生为 DNS SAN，CN 不是合法 DNS 名就拒绝签发。适配器永远显式传 SAN， 所以任意 alias 都能签出来；这里只负责把
     * CN 归一化成合法的 DNS 标签。
     */
    static String dnsName(String cn) {
        String normalized = DNS_SAFE.matcher(cn.toLowerCase(Locale.ROOT)).replaceAll("-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "").replaceAll("\\.+$", "");
        if (normalized.isEmpty()) {
            normalized = "seedj-identity";
        }
        return normalized.length() > 63 ? normalized.substring(0, 63) : normalized;
    }

    /**
     * 调 coord 并统一映射错误。
     *
     * <p>coord 的 PKI 存储建在 KV 上，刚写完就回读会撞上 {@code read_ts exceeds safe_ts}（agent 自己在 消息里写 {@code
     * retry later}）。这里对<b>幂等</b>调用做有限重试；{@code rotateCert} 不是幂等的， 重试会多转一次，所以只调用一次。
     */
    private <T> T call(String action, java.util.function.Supplier<T> body) {
        return call(action, true, body);
    }

    private <T> T call(String action, boolean idempotent, java.util.function.Supplier<T> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return body.get();
            } catch (CoordException e) {
                if (idempotent && attempt < RETRY_ATTEMPTS && transientFailure(e)) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                throw CoordErrors.pki(e, endpoint, action);
            }
        }
    }

    /** 可重试的传输/时序失败。{@code INTERNAL} 只在消息点名 safe_ts 回读滞后时重试。 */
    private static boolean transientFailure(CoordException e) {
        return switch (e.getErrorCode()) {
            case UNAVAILABLE,
                            DEADLINE_EXCEEDED,
                            NOT_LEADER,
                            SAFE_TS_LAG,
                            EPOCH_STALE,
                            TXN_CONFLICT,
                            COMMIT_REJECTED ->
                    true;
            case INTERNAL -> {
                String message = e.getMessage();
                yield message != null
                        && (message.contains("safe_ts") || message.contains("retry later"));
            }
            default -> false;
        };
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new PkiException(PkiErrorCode.PROVIDER_CLOSED, "coord pki provider is closed");
        }
    }
}
