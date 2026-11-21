package cn.byteforce.seedj.provider.pki;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

/**
 * TLS 配置描述，不持有 {@code SSLContext}。
 *
 * <p>这是「私钥不可导出」场景的唯一出口：调用方拿到 {@link #keyMaterial()} 后自行决定把 {@code engineSign} 委托给 {@link
 * PkiProvider#sign}。
 */
public record SslConfig(
        List<X509Certificate> certificateChain,
        KeyMaterial keyMaterial,
        ClientAuth clientAuth,
        List<X509Certificate> trustAnchors) {

    public SslConfig {
        certificateChain = certificateChain == null ? List.of() : List.copyOf(certificateChain);
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        Objects.requireNonNull(clientAuth, "clientAuth");
        trustAnchors = trustAnchors == null ? List.of() : List.copyOf(trustAnchors);
    }

    /** 私钥是否必须由 provider 远程签名。 */
    public boolean delegatingSigning() {
        return keyMaterial instanceof KeyMaterial.CoordManaged;
    }
}
