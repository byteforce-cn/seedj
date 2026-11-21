package cn.byteforce.seedj.provider.pki;

import java.time.Instant;
import java.util.Objects;

/**
 * provider 与当前 issuer 的只读描述。
 *
 * @param provider provider 名称（{@code local} / {@code coord}）
 * @param issuerRef 调用方可用于 {@link PkiProvider#issuerCertificate} 的引用
 * @param issuerSubject CA subject DN
 * @param issuerNotAfter CA 到期时间
 * @param managedPrivateKeys provider 是否默认托管私钥
 */
public record PkiInfo(
        String provider,
        String issuerRef,
        String issuerSubject,
        Instant issuerNotAfter,
        boolean managedPrivateKeys) {

    public PkiInfo {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(issuerRef, "issuerRef");
        Objects.requireNonNull(issuerSubject, "issuerSubject");
        Objects.requireNonNull(issuerNotAfter, "issuerNotAfter");
    }
}
