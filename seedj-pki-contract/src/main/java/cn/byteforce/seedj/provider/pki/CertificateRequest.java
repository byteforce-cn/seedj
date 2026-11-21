package cn.byteforce.seedj.provider.pki;

import java.time.Duration;
import java.util.List;

/**
 * 签发请求。
 *
 * <p>{@code subject} 可以是完整 DN（{@code CN=svc,O=Byteforce}）或裸 CN；provider 只承诺使用其中的
 * CN。{@link PkiProvider#signCsr} 的 subject/SAN 来自 CSR，此处的 {@code subject} 会被忽略，因此可用
 * {@link #csr} 构造一个只带有效期与用途的请求。
 *
 * <p>列表字段永远非 null；{@code validity == null} 表示 provider 默认有效期。
 */
public record CertificateRequest(
        String subject,
        List<String> dnsNames,
        List<String> ipAddresses,
        Duration validity,
        CertificateUsage usage) {

    public CertificateRequest {
        dnsNames = dnsNames == null ? List.of() : List.copyOf(dnsNames);
        ipAddresses = ipAddresses == null ? List.of() : List.copyOf(ipAddresses);
        if (validity != null && (validity.isZero() || validity.isNegative())) {
            throw new IllegalArgumentException("validity 必须为正");
        }
        usage = usage == null ? CertificateUsage.BOTH : usage;
    }

    public static CertificateRequest of(String subject) {
        return new CertificateRequest(subject, List.of(), List.of(), null, CertificateUsage.BOTH);
    }

    public static CertificateRequest server(
            String subject, List<String> dnsNames, List<String> ipAddresses) {
        return new CertificateRequest(
                subject, dnsNames, ipAddresses, null, CertificateUsage.SERVER);
    }

    public static CertificateRequest client(String subject) {
        return new CertificateRequest(subject, List.of(), List.of(), null, CertificateUsage.CLIENT);
    }

    /** CSR 签发只使用有效期与用途；CN/SAN 由 CSR 决定。 */
    public static CertificateRequest csr(Duration validity, CertificateUsage usage) {
        return new CertificateRequest(null, List.of(), List.of(), validity, usage);
    }

    public String requireSubject() {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject 不能为空");
        }
        return subject;
    }
}
