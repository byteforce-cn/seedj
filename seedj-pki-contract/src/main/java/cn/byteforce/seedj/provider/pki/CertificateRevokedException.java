package cn.byteforce.seedj.provider.pki;

import java.math.BigInteger;

/**
 * alias 指向的证书已被吊销。
 *
 * <p>这是 {@link PkiProvider#signingIdentity} 的显式拒绝：不静默重签、不返回旧证书。恢复路径只有 {@link
 * PkiProvider#rotateSigningIdentity}。
 */
public class CertificateRevokedException extends PkiException {

    private final String alias;
    private final BigInteger serialNumber;

    public CertificateRevokedException(String alias, BigInteger serialNumber) {
        super(
                PkiErrorCode.REVOKED,
                "signing identity "
                        + alias
                        + " was revoked (serial "
                        + hex(serialNumber)
                        + "); call rotateSigningIdentity to replace it");
        this.alias = alias;
        this.serialNumber = serialNumber;
    }

    public CertificateRevokedException(BigInteger serialNumber) {
        this(null, serialNumber);
    }

    public String alias() {
        return alias;
    }

    public BigInteger serialNumber() {
        return serialNumber;
    }

    private static String hex(BigInteger serial) {
        return serial == null ? "unknown" : serial.toString(16);
    }
}
