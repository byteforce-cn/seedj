package cn.byteforce.seedj.infra.local.pki;

import cn.byteforce.seedj.provider.pki.CertificateRequest;
import cn.byteforce.seedj.provider.pki.CertificateUsage;
import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLNumber;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/** 本地 PKI 的证书与 CRL 构建。只做 BouncyCastle 编解码，不读写状态文件。 */
final class BcPkiCertificates {

    static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    static final Duration DEFAULT_VALIDITY = Duration.ofDays(365);

    private BcPkiCertificates() {}

    /** 校验过持有证明的 CSR 材料。 */
    record Csr(X500Name subject, PublicKey publicKey, GeneralNames altNames) {}

    static Csr parseCsr(byte[] csrDer) {
        try {
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(csrDer);
            if (!csr.isSignatureValid(
                    new JcaContentVerifierProviderBuilder()
                            .build(csr.getSubjectPublicKeyInfo()))) {
                throw new PkiException(PkiErrorCode.INVALID_REQUEST, "CSR 的持有证明签名不合法");
            }
            PublicKey publicKey = new JcaPKCS10CertificationRequest(csr).getPublicKey();
            Extensions requested = csr.getRequestedExtensions();
            GeneralNames altNames =
                    requested == null
                                    || requested.getExtension(Extension.subjectAlternativeName)
                                            == null
                            ? null
                            : GeneralNames.fromExtensions(
                                    requested, Extension.subjectAlternativeName);
            return new Csr(csr.getSubject(), publicKey, altNames);
        } catch (PkiException e) {
            throw e;
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INVALID_REQUEST, "解析 CSR 失败", e);
        }
    }

    static X509Certificate issue(
            X500Name caName,
            PrivateKey caKey,
            X500Name subject,
            PublicKey publicKey,
            GeneralNames subjectAltNames,
            Duration validity,
            CertificateUsage usage,
            BigInteger serial,
            Instant now) {
        try {
            Instant notBefore = now.truncatedTo(ChronoUnit.SECONDS);
            Instant notAfter = notBefore.plus(validity == null ? DEFAULT_VALIDITY : validity);
            JcaX509v3CertificateBuilder builder =
                    new JcaX509v3CertificateBuilder(
                            caName,
                            serial,
                            Date.from(notBefore),
                            Date.from(notAfter),
                            subject,
                            publicKey);

            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            int keyUsageBits = KeyUsage.digitalSignature;
            if (usage != CertificateUsage.CLIENT) {
                keyUsageBits |= KeyUsage.keyEncipherment;
            }
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));

            if (usage == CertificateUsage.SERVER) {
                builder.addExtension(
                        Extension.extendedKeyUsage,
                        false,
                        new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            } else if (usage == CertificateUsage.CLIENT) {
                builder.addExtension(
                        Extension.extendedKeyUsage,
                        false,
                        new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            }

            if (subjectAltNames != null) {
                builder.addExtension(
                        Extension.subjectAlternativeName, false, subjectAltNames);
            }

            return new JcaX509CertificateConverter()
                    .getCertificate(
                            builder.build(
                                    new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                                            .build(caKey)));
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "签发证书失败: " + subject, e);
        }
    }

    static byte[] crl(
            X500Name caName,
            PrivateKey caKey,
            List<PkiStateFile.RevokedEntry> entries,
            long number,
            Instant now,
            Duration validity) {
        try {
            Instant thisUpdate = now.truncatedTo(ChronoUnit.SECONDS);
            X509v2CRLBuilder builder =
                    new X509v2CRLBuilder(caName, Date.from(thisUpdate))
                            .setNextUpdate(Date.from(thisUpdate.plus(validity)));
            for (PkiStateFile.RevokedEntry entry : entries) {
                builder.addCRLEntry(
                        entry.serial(), Date.from(entry.revokedAt()), entry.reason().rfcCode());
            }
            builder.addExtension(
                    Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(number)));
            return new JcaX509CRLConverter()
                    .getCRL(
                            builder.build(
                                    new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(caKey)))
                    .getEncoded();
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "生成 CRL 失败", e);
        }
    }

    static GeneralNames subjectAltNames(CertificateRequest request) {
        List<GeneralName> names = new ArrayList<>();
        for (String dns : request.dnsNames()) {
            names.add(new GeneralName(GeneralName.dNSName, dns));
        }
        for (String ip : request.ipAddresses()) {
            names.add(new GeneralName(GeneralName.iPAddress, ip));
        }
        return names.isEmpty() ? null : new GeneralNames(names.toArray(GeneralName[]::new));
    }

    static X500Name subjectName(String subject) {
        return new X500Name(subject.contains("=") ? subject : "CN=" + subject);
    }
}
