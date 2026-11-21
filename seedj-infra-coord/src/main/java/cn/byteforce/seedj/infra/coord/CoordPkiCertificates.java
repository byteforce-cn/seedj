package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.provider.pki.PkiErrorCode;
import cn.byteforce.seedj.provider.pki.PkiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * coord PKI 的 PEM 材料到 JSSE 对象的转换。
 *
 * <p>coord 返回的是 PEM 文本（叶子 + 自签 CA）。0.4.4 起托管私钥不再随响应返回，因此这里只解析证书； 私钥只有在调用方自持（{@code
 * KeyMaterial.External}）时才会传入。
 */
final class CoordPkiCertificates {

    private static final char[] NO_PASSWORD = new char[0];

    private CoordPkiCertificates() {}

    static X509Certificate certificate(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate)
                    factory.generateCertificate(
                            new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PkiException(
                    PkiErrorCode.INTERNAL_ERROR, "cannot parse X.509 certificate", e);
        }
    }

    /**
     * 双向身份的 {@link SSLContext}。
     *
     * @param chain 叶子到 CA 的证书链；{@code null} 表示匿名客户端
     * @param key 调用方自持的私钥；{@code null} 表示匿名客户端
     * @param ca 信任根
     */
    static SSLContext sslContext(
            X509Certificate leaf, PrivateKey key, List<X509Certificate> chain, X509Certificate ca) {
        try {
            KeyManagerFactory keyManagers = null;
            if (leaf != null && key != null) {
                KeyStore identity = KeyStore.getInstance("PKCS12");
                identity.load(null, null);
                identity.setKeyEntry(
                        "identity",
                        key,
                        NO_PASSWORD,
                        (chain == null ? List.of(leaf) : chain).toArray(X509Certificate[]::new));
                keyManagers =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(identity, NO_PASSWORD);
            }

            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            trust.setCertificateEntry("ca", ca);
            TrustManagerFactory trustManagers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(),
                    null);
            return context;
        } catch (Exception e) {
            throw new PkiException(PkiErrorCode.INTERNAL_ERROR, "cannot build SSLContext", e);
        }
    }

    /** 证书链：coord 只有叶子 + 自签 CA，没有中间证书。 */
    static List<X509Certificate> chain(X509Certificate leaf, X509Certificate ca) {
        List<X509Certificate> chain = new ArrayList<>(2);
        chain.add(leaf);
        if (ca != null && !ca.equals(leaf)) {
            chain.add(ca);
        }
        return List.copyOf(chain);
    }
}
