package cn.byteforce.seedj.infra.coord.fixture;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.pki.PkiCertInfo;
import cn.byteforce.coord.sdk.pki.PkiCertStatus;
import cn.byteforce.coord.sdk.pki.PkiCertSummary;
import cn.byteforce.coord.sdk.pki.PkiCertUsage;
import cn.byteforce.coord.sdk.pki.PkiClient;
import cn.byteforce.coord.sdk.pki.PkiCrl;
import cn.byteforce.coord.sdk.pki.PkiCrlFormat;
import cn.byteforce.coord.sdk.pki.PkiRevocationInfo;
import cn.byteforce.coord.sdk.pki.PkiRevocationReason;
import cn.byteforce.coord.sdk.pki.PkiTidyResult;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/**
 * 内存版 coord PKI 0.4.4：只为适配器的单元测试服务。
 *
 * <p>行为对齐真实 agent 的契约面：按 CN get-or-create、轮换换新 serial、吊销永久且幂等、
 * {@code GetCertByCN} 对已吊销的当前证书返回 NOT_FOUND、CRL 带吊销条目。证书由测试内的自签 CA
 * 真实签发，所以 PEM 解析、TLS 与 CRL 路径都被覆盖。
 */
public final class FakePkiClient implements PkiClient {

    private record CertRecord(
            String cn,
            String serial,
            String parentSerial,
            X509Certificate certificate,
            String keyName,
            int keyVersion,
            String status,
            boolean revoked,
            PkiRevocationReason reason,
            Instant revokedAt) {

        boolean expired(Instant now) {
            return !now.isBefore(certificate.getNotAfter().toInstant());
        }

        String effectiveStatus(Instant now) {
            if (revoked) {
                return "revoked";
            }
            return expired(now) ? "expired" : status;
        }
    }

    private final KeyPair caKeyPair;

    private final X509Certificate caCertificate;

    private final X500Name caName;

    private final Map<String, CertRecord> activeByCn = new LinkedHashMap<>();

    private final Map<String, CertRecord> bySerial = new LinkedHashMap<>();

    private final AtomicLong serials = new AtomicLong(1000);

    private final AtomicLong crlNumbers = new AtomicLong(1);

    private boolean caInitialized = true;

    private boolean down;

    public FakePkiClient() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.caKeyPair = generator.generateKeyPair();
            this.caName = name("Coord Test CA");
            Date now = new Date();
            JcaX509v3CertificateBuilder builder =
                    new JcaX509v3CertificateBuilder(
                            caName,
                            BigInteger.valueOf(1),
                            now,
                            new Date(now.getTime() + 3_650L * 86_400_000L),
                            caName,
                            caKeyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            this.caCertificate =
                    new JcaX509CertificateConverter()
                            .getCertificate(
                                    builder.build(
                                            new JcaContentSignerBuilder("SHA256withRSA")
                                                    .build(caKeyPair.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void down(boolean value) {
        this.down = value;
    }

    public void caInitialized(boolean value) {
        this.caInitialized = value;
    }

    public String currentSerial(String cn) {
        CertRecord record = activeByCn.get(cn);
        return record == null ? null : record.serial();
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
        return issueCert(
                commonName, ttlSeconds, dnsNames, ipAddresses, PkiCertUsage.BOTH);
    }

    @Override
    public PkiCertInfo issueCert(
            String commonName,
            long ttlSeconds,
            List<String> dnsNames,
            List<String> ipAddresses,
            PkiCertUsage usage) {
        check();
        CertRecord current = activeByCn.get(commonName);
        if (current != null && current.revoked()) {
            throw new CoordException(
                    ErrorCode.FAILED_PRECONDITION,
                    "certificate for " + commonName + " is revoked; rotate explicitly");
        }
        if (current != null && !current.expired(Instant.now())) {
            return info(current);
        }
        return rotate(commonName, ttlSeconds, dnsNames, ipAddresses, usage);
    }

    @Override
    public PkiCertInfo renewCert(String serial, long ttlSeconds) {
        check();
        CertRecord record =
                Optional.ofNullable(bySerial.get(serial))
                        .orElseThrow(() -> new CoordException(ErrorCode.NOT_FOUND, "no such serial"));
        return rotate(record.cn(), ttlSeconds, List.of(), List.of(), PkiCertUsage.BOTH);
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
        return rotateCert(commonName, ttlSeconds, dnsNames, ipAddresses, PkiCertUsage.BOTH);
    }

    @Override
    public PkiCertInfo rotateCert(
            String commonName,
            long ttlSeconds,
            List<String> dnsNames,
            List<String> ipAddresses,
            PkiCertUsage usage) {
        check();
        return rotate(commonName, ttlSeconds, dnsNames, ipAddresses, usage);
    }

    @Override
    public List<PkiCertSummary> listCerts(String commonName) {
        check();
        return bySerial.values().stream()
                .filter(record -> record.cn().equals(commonName))
                .map(this::summary)
                .toList();
    }

    @Override
    public PkiCertInfo getCertByCN(String commonName) {
        check();
        CertRecord record = activeByCn.get(commonName);
        if (record == null || record.revoked() || record.expired(Instant.now())) {
            throw new CoordException(ErrorCode.NOT_FOUND, "no active certificate for CN");
        }
        return info(record);
    }

    @Override
    public PkiCertSummary getCertBySerial(String serial) {
        check();
        return summary(
                Optional.ofNullable(bySerial.get(serial))
                        .orElseThrow(() -> new CoordException(ErrorCode.NOT_FOUND, "no such serial")));
    }

    @Override
    public PkiCertInfo signCsr(byte[] csrDer, long ttlSeconds, PkiCertUsage usage) {
        check();
        try {
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(csrDer);
            JcaPKCS10CertificationRequest jca = new JcaPKCS10CertificationRequest(csr);
            String cn = csr.getSubject().getRDNs(BCStyle.CN)[0].getFirst().getValue().toString();
            GeneralNames sans = null;
            if (csr.getRequestedExtensions() != null
                    && csr.getRequestedExtensions().getExtension(Extension.subjectAlternativeName)
                            != null) {
                sans =
                        GeneralNames.fromExtensions(
                                csr.getRequestedExtensions(), Extension.subjectAlternativeName);
            }
            String serial = nextSerial();
            X509Certificate certificate =
                    sign(
                            csr.getSubject(),
                            jca.getPublicKey(),
                            serialNumber(serial),
                            ttlSeconds,
                            usage,
                            sans);
            CertRecord record =
                    new CertRecord(
                            cn,
                            serial,
                            null,
                            certificate,
                            "",
                            0,
                            "active",
                            false,
                            null,
                            null);
            bySerial.put(serial, record);
            return info(record);
        } catch (CoordException e) {
            throw e;
        } catch (Exception e) {
            throw new CoordException(ErrorCode.INVALID_ARGUMENT, "invalid CSR: " + e.getMessage());
        }
    }

    @Override
    public PkiCertInfo signCsr(byte[] csrDer) {
        return signCsr(csrDer, 86_400L, PkiCertUsage.BOTH);
    }

    @Override
    public boolean verifyCert(String certPem) {
        check();
        try {
            X509Certificate certificate =
                    (X509Certificate)
                            java.security.cert.CertificateFactory.getInstance("X.509")
                                    .generateCertificate(
                                            new java.io.ByteArrayInputStream(
                                                    certPem.getBytes(StandardCharsets.UTF_8)));
            certificate.verify(caCertificate.getPublicKey());
            CertRecord record = bySerial.get(certificate.getSerialNumber().toString(16));
            return record != null
                    && !record.revoked()
                    && !record.expired(Instant.now());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getCaCert() {
        check();
        return caInitialized ? pem(caCertificate) : "";
    }

    @Override
    public PkiRevocationInfo revokeCert(String serialNumber, PkiRevocationReason reason) {
        check();
        CertRecord record = bySerial.get(serialNumber);
        if (record == null) {
            throw new CoordException(ErrorCode.NOT_FOUND, "no such serial");
        }
        if (record.revoked()) {
            return revocation(record);
        }
        CertRecord revoked =
                new CertRecord(
                        record.cn(),
                        record.serial(),
                        record.parentSerial(),
                        record.certificate(),
                        record.keyName(),
                        record.keyVersion(),
                        "revoked",
                        true,
                        reason == null ? PkiRevocationReason.UNSPECIFIED : reason,
                        Instant.now());
        bySerial.put(serialNumber, revoked);
        activeByCn.computeIfPresent(
                revoked.cn(), (cn, current) -> current.serial().equals(serialNumber) ? revoked : current);
        return revocation(revoked);
    }

    @Override
    public PkiCertStatus getCertStatus(String serialNumber) {
        check();
        CertRecord record =
                Optional.ofNullable(bySerial.get(serialNumber))
                        .orElseThrow(() -> new CoordException(ErrorCode.NOT_FOUND, "no such serial"));
        long revokedAt =
                record.revoked() && record.revokedAt() != null
                        ? record.revokedAt().getEpochSecond()
                        : 0L;
        return new PkiCertStatus(
                record.serial(),
                record.serial(),
                record.cn(),
                record.effectiveStatus(Instant.now()),
                record.certificate().getNotBefore().toInstant().getEpochSecond(),
                record.certificate().getNotAfter().toInstant().getEpochSecond(),
                revokedAt,
                record.reason(),
                "test");
    }

    @Override
    public PkiCrl getCrl(PkiCrlFormat format) {
        check();
        return crl(format, false);
    }

    @Override
    public PkiCrl rotateCrl(PkiCrlFormat format) {
        check();
        return crl(format, true);
    }

    @Override
    public PkiTidyResult tidy(long retentionSeconds) {
        check();
        Instant cutoff = Instant.now().minusSeconds(Math.max(0L, retentionSeconds));
        List<String> remove =
                bySerial.values().stream()
                        .filter(record -> !record.revoked())
                        .filter(record -> record.expired(cutoff))
                        .map(CertRecord::serial)
                        .toList();
        remove.forEach(bySerial::remove);
        activeByCn.values().removeIf(record -> remove.contains(record.serial()));
        return new PkiTidyResult(retentionSeconds, remove.size());
    }

    // ==================== 内部 ====================

    private PkiCertInfo rotate(
            String commonName,
            long ttlSeconds,
            List<String> dnsNames,
            List<String> ipAddresses,
            PkiCertUsage usage) {
        CertRecord previous = activeByCn.get(commonName);
        String serial = nextSerial();
        try {
            List<GeneralName> names = new ArrayList<>();
            List<String> dns = dnsNames.isEmpty() ? List.of(commonName.replace(':', '-')) : dnsNames;
            dns.forEach(name -> names.add(new GeneralName(GeneralName.dNSName, name)));
            ipAddresses.forEach(ip -> names.add(new GeneralName(GeneralName.iPAddress, ip)));
            GeneralNames sans =
                    names.isEmpty()
                            ? null
                            : new GeneralNames(names.toArray(GeneralName[]::new));
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            X509Certificate certificate =
                    sign(
                            name(commonName),
                            generator.generateKeyPair().getPublic(),
                            serialNumber(serial),
                            ttlSeconds,
                            usage,
                            sans);
            CertRecord record =
                    new CertRecord(
                            commonName,
                            serial,
                            previous == null ? null : previous.serial(),
                            certificate,
                            "pki-" + commonName.replace(':', '-'),
                            1,
                            "active",
                            false,
                            null,
                            null);
            if (previous != null && !previous.revoked()) {
                bySerial.put(
                        previous.serial(),
                        new CertRecord(
                                previous.cn(),
                                previous.serial(),
                                previous.parentSerial(),
                                previous.certificate(),
                                previous.keyName(),
                                previous.keyVersion(),
                                "retired",
                                false,
                                null,
                                null));
            }
            bySerial.put(serial, record);
            activeByCn.put(commonName, record);
            return info(record);
        } catch (Exception e) {
            throw new CoordException(ErrorCode.INTERNAL, "issue failed: " + e.getMessage());
        }
    }

    private X509Certificate sign(
            X500Name subject,
            java.security.PublicKey publicKey,
            BigInteger serial,
            long ttlSeconds,
            PkiCertUsage usage,
            GeneralNames sans)
            throws Exception {
        Date now = new Date();
        Date notAfter = new Date(now.getTime() + Math.max(1L, ttlSeconds) * 1000L);
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        caName,
                        serial,
                        now,
                        notAfter,
                        subject,
                        publicKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        int bits = KeyUsage.digitalSignature;
        if (usage != PkiCertUsage.CLIENT) {
            bits |= KeyUsage.keyEncipherment;
        }
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(bits));
        if (usage == PkiCertUsage.SERVER) {
            builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        } else if (usage == PkiCertUsage.CLIENT) {
            builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        }
        if (sans != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, sans);
        }
        return new JcaX509CertificateConverter()
                .getCertificate(
                        builder.build(
                                new JcaContentSignerBuilder("SHA256withRSA")
                                        .build(caKeyPair.getPrivate())));
    }

    private PkiCrl crl(PkiCrlFormat format, boolean rotate) {
        try {
            long number = rotate ? crlNumbers.incrementAndGet() : crlNumbers.get();
            Instant now = Instant.now();
            X509v2CRLBuilder builder =
                    new X509v2CRLBuilder(caName, Date.from(now))
                            .setNextUpdate(Date.from(now.plusSeconds(86_400)));
            for (CertRecord record : bySerial.values()) {
                if (record.revoked()) {
                    builder.addCRLEntry(
                            serialNumber(record.serial()),
                            Date.from(record.revokedAt()),
                            record.reason().rfcCode());
                }
            }
            builder.addExtension(
                    Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(number)));
            byte[] der =
                    new JcaX509CRLConverter()
                            .getCRL(
                                    builder.build(
                                            new JcaContentSignerBuilder("SHA256withRSA")
                                                    .build(caKeyPair.getPrivate())))
                            .getEncoded();
            return new PkiCrl(
                    der,
                    format == null ? PkiCrlFormat.PEM : format,
                    number,
                    now.getEpochSecond(),
                    now.plusSeconds(86_400).getEpochSecond());
        } catch (Exception e) {
            throw new CoordException(ErrorCode.INTERNAL, "CRL failed: " + e.getMessage());
        }
    }

    private PkiCertInfo info(CertRecord record) {
        return new PkiCertInfo(
                record.cn(),
                pem(record.certificate()),
                record.keyName(),
                record.keyVersion(),
                record.certificate().getNotBefore().toInstant().getEpochSecond(),
                record.certificate().getNotAfter().toInstant().getEpochSecond(),
                record.serial(),
                record.effectiveStatus(Instant.now()),
                record.parentSerial());
    }

    private PkiCertSummary summary(CertRecord record) {
        return new PkiCertSummary(
                record.cn(),
                pem(record.certificate()),
                record.certificate().getNotBefore().toInstant().getEpochSecond(),
                record.certificate().getNotAfter().toInstant().getEpochSecond(),
                record.serial(),
                record.effectiveStatus(Instant.now()),
                record.parentSerial());
    }

    private PkiRevocationInfo revocation(CertRecord record) {
        return new PkiRevocationInfo(
                record.serial(),
                record.serial(),
                record.cn(),
                record.revokedAt().getEpochSecond(),
                record.reason(),
                "test");
    }

    private BigInteger serialNumber(String serial) {
        return new BigInteger(serial, 16);
    }

    private String nextSerial() {
        return BigInteger.valueOf(serials.incrementAndGet()).toString(16);
    }

    private static X500Name name(String cn) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, cn).build();
    }

    private static String pem(X509Certificate certificate) {
        try {
            String base64 =
                    java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                            .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void check() {
        if (down) {
            throw new CoordException(ErrorCode.UNAVAILABLE, "agent unreachable");
        }
    }
}
