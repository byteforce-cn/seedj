package cn.byteforce.seedj.provider.pki;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一张已签发的证书及其密钥归属。
 *
 * <p>serial 以证书内嵌值为准（{@link X509Certificate#getSerialNumber()}），不要另外维护一份记录
 * serial：吊销、CRL、第三方验签引用的是同一个值。
 */
public record IssuedCertificate(
        X509Certificate certificate, List<X509Certificate> chain, KeyMaterial keyMaterial) {

    public IssuedCertificate {
        Objects.requireNonNull(certificate, "certificate");
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        chain = chain == null || chain.isEmpty() ? List.of(certificate) : List.copyOf(chain);
    }

    public BigInteger serialNumber() {
        return certificate.getSerialNumber();
    }

    public Instant notBefore() {
        return certificate.getNotBefore().toInstant();
    }

    public Instant notAfter() {
        return certificate.getNotAfter().toInstant();
    }

    public boolean expired(Instant now) {
        return !now.isBefore(notAfter());
    }

    /**
     * 绑定调用方自持的私钥（CSR 签发的典型用法）。
     *
     * @throws IllegalStateException 如果证书的密钥由 provider 托管
     */
    public IssuedCertificate withPrivateKey(PrivateKey privateKey) {
        Objects.requireNonNull(privateKey, "privateKey");
        if (!(keyMaterial instanceof KeyMaterial.External)) {
            throw new IllegalStateException(
                    "certificate key is managed by the provider; it cannot be bound locally");
        }
        return new IssuedCertificate(certificate, chain, new KeyMaterial.External(privateKey));
    }
}
