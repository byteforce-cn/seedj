package cn.byteforce.seedj.pki;

import javax.net.ssl.SSLContext;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PKI 业务入口，同时是 provider 实现契约。
 *
 * <p>业务方只需要一个意图：{@link #signingIdentity(String)} 取稳定签名身份；
 * 其余方法服务于测试与运维场景。
 *
 * <p>实现必须线程安全；{@code close()} 幂等且不抛。
 */
public interface PkiProvider extends AutoCloseable {

    enum CertificateUsage {
        /** 服务端证书：KeyUsage=digitalSignature|keyEncipherment，EKU=serverAuth */
        SERVER,
        /** 客户端证书：KeyUsage=digitalSignature，EKU=clientAuth */
        CLIENT,
        /** 双向用途：KeyUsage=digitalSignature|keyEncipherment，无 EKU */
        BOTH
    }

    record CertificateRequest(
            String subject,
            List<String> dnsNames,
            List<String> ipAddresses,
            Duration validity,
            KeyPair keyPair,
            CertificateUsage usage
    ) {
        public static CertificateRequest of(String subject) {
            return new CertificateRequest(subject, List.of(), List.of(),
                    Duration.ofDays(365), null, CertificateUsage.BOTH);
        }

        public static CertificateRequest server(String subject,
                                                List<String> dnsNames,
                                                List<String> ipAddresses) {
            return new CertificateRequest(subject, dnsNames, ipAddresses,
                    Duration.ofDays(365), null, CertificateUsage.SERVER);
        }

        public static CertificateRequest client(String subject) {
            return new CertificateRequest(subject, List.of(), List.of(),
                    Duration.ofDays(365), null, CertificateUsage.CLIENT);
        }
    }

    record IssuedCertificate(
            X509Certificate certificate,
            List<X509Certificate> chain,
            PrivateKey privateKey,
            String serialNumber,
            Instant notBefore,
            Instant notAfter
    ) {}

    enum CertificateStatus { VALID, REVOKED, EXPIRED, UNKNOWN }

    enum RevocationReason {
        UNSPECIFIED, KEY_COMPROMISE, CA_COMPROMISE, SUPERSEDED, CESSATION
    }

    IssuedCertificate issue(CertificateRequest request);

    /**
     * 获取 alias 对应的长期签名身份（密钥对 + CA 签发证书）。
     *
     * <p>同一 alias 在同一 provider 部署（同一 CA + 同一状态存储）下幂等：
     * 证书有效时原样返回；证书过期时用同一密钥对重签，{@code serialNumber} 不变；
     * 身份已被 {@link #revoke} 时抛 {@link PkiException}，不静默恢复。
     *
     * <p>身份与当前 CA 绑定：更换 CA 后必须调用
     * {@link #rotateSigningIdentity(String, RevocationReason)} 迁移。
     *
     * @param alias 业务别名，匹配 {@code [A-Za-z0-9][A-Za-z0-9._-]*}
     */
    IssuedCertificate signingIdentity(String alias);

    /**
     * 轮换 alias 的签名身份：以 {@code reason} 吊销当前身份、丢弃其密钥材料、签发新身份。
     *
     * <p>alias 没有当前身份时等价于 {@link #signingIdentity(String)}，不产生吊销记录。
     * 旧 serial 永久保持 REVOKED，不会被后续调用复活。
     */
    IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason);

    void revoke(String serialNumber, RevocationReason reason);

    CertificateStatus status(String serialNumber);

    X509Certificate caCertificate();

    /**
     * 构建带双向身份的 SSLContext：
     * <ul>
     *   <li>KeyManager 使用 {@code identity} 的私钥 + 证书链（客户端/服务端出示身份）；</li>
     *   <li>TrustManager 信任本 PKI CA（验证对端）。</li>
     * </ul>
     *
     * @param identity 本端身份证书；{@code null} 表示匿名客户端（单向 TLS）
     */
    SSLContext sslContext(IssuedCertificate identity);

    /** 匿名客户端（仅信任 CA，不出示身份） */
    default SSLContext trustContext() {
        return sslContext(null);
    }

    @Override
    default void close() {
    }
}