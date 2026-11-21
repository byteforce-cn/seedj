package cn.byteforce.seedj.provider.pki;

import cn.byteforce.seedj.observability.HealthStatus;
import cn.byteforce.seedj.observability.ObservableProvider;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Coord PKI + Transit 引擎的 PKI 契约。
 *
 * <p><b>核心约束：</b>私钥默认不出 Coord。需要签名时走 {@link #sign}， 不要试图从 {@link IssuedCertificate} 拿 {@code
 * PrivateKey}。
 *
 * <p><b>状态归属：</b>证书、吊销列表、CRL 全部由 Coord 维护； 本接口不提供本地状态存储。
 *
 * <p><b>alias 映射：</b>Coord 原生没有 alias 概念。 {@link #signingIdentity} 和 {@link #rotateSigningIdentity}
 * 的幂等性 由实现侧在 Coord KV 中维护的映射表保证，不是 Coord 本身的行为。 如果不需要 alias 层，业务方可以直接使用 {@link #issue} / {@link
 * #signCsr}。
 */
public interface PkiProvider extends ObservableProvider, AutoCloseable {

    // ==================== 1. 证书签发 ====================

    /**
     * 在指定 role 下签发证书，密钥由 Coord 生成（对应 {@code POST /pki/issue/:role}）。
     *
     * <p>返回的 {@link IssuedCertificate} 的 {@code keyMaterial} 通常是 {@link
     * KeyMaterial.CoordManaged}，私钥不可导出。
     */
    IssuedCertificate issue(String roleName, CertificateRequest request);

    /**
     * 签发 CSR（对应 {@code POST /pki/sign/:role}）。
     *
     * <p>调用方自行生成密钥对并构造 CSR。返回的 {@link IssuedCertificate} 不包含私钥——私钥在调用方自己手里，因此 {@code keyMaterial} 是
     * {@link KeyMaterial.External}。调用方拿到证书后可用 {@link IssuedCertificate#withPrivateKey} 绑定自己的私钥，再交给
     * {@link #sslContext}。
     */
    IssuedCertificate signCsr(String roleName, byte[] csrDer, CertificateRequest request);

    // ==================== 2. 签名 / 验签（Coord Transit） ====================

    /**
     * 用 Transit 密钥签名（对应 {@code POST /transit/sign/:name}）。
     *
     * <p>如果调用方传的是 hash 值而非原文，{@code prehashed} 必须为 {@code true}， 并同时指定 {@code
     * signatureAlgorithm}（例如 {@code pkcs1v15}、{@code pss}、{@code ed25519}）。Transit 的密钥默认 {@code
     * exportable=false}，私钥永不离开 Coord。
     */
    byte[] sign(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data);

    /** 用 Transit 密钥验签（对应 {@code POST /transit/verify/:name}）。 */
    boolean verify(
            String keyName,
            String hashAlgorithm,
            boolean prehashed,
            String signatureAlgorithm,
            byte[] data,
            byte[] signature);

    // ==================== 3. 吊销与 CRL ====================

    /** 按 serial 吊销（对应 {@code POST /pki/revoke}）。 */
    void revoke(BigInteger serialNumber, RevocationReason reason);

    /** 获取当前 CRL（对应 {@code GET /pki/crl}）。 */
    byte[] getCrl();

    /** 轮换 CRL（对应 {@code GET /pki/crl/rotate}）。 */
    void rotateCrl();

    /**
     * 清理过期证书和 CRL（对应 {@code POST /pki/tidy}）。
     *
     * <p>被吊销的证书在 tidy 执行前会保留在 Coord 的存储中。
     */
    void tidy(TidyRequest request);

    // ==================== 4. 证书 / CA 查询 ====================

    /** 按 serial 读取证书。Coord 不直接提供“证书状态查询”API， 状态由调用方结合 CRL 和过期时间自行推导。 */
    Optional<X509Certificate> readCertificate(BigInteger serialNumber);

    /**
     * 获取指定 issuer 的 CA 证书（对应 {@code GET /pki/issuer/:issuer_ref}）。 {@code issuerRef} 可以是 Coord 生成的
     * ID、字面量 {@code "default"}， 或 issuer 的 name。
     */
    X509Certificate issuerCertificate(String issuerRef);

    /** 获取指定 issuer 的 CA 链。 */
    List<X509Certificate> caChain(String issuerRef);

    // ==================== 5. 身份映射（可选 KV 层） ====================

    /**
     * 获取 alias 对应的当前身份。
     *
     * <p><b>这不是 Coord 原生概念。</b>实现需要在 KV 中维护 {@code alias -> roleName + serial} 的映射。
     *
     * <p>如果 alias 对应的证书已被吊销，抛 {@link CertificateRevokedException}， 不静默重签。
     */
    IssuedCertificate signingIdentity(String alias);

    /**
     * 轮换：吊销当前 serial，用同一 role 重新签发，更新 KV 映射。
     *
     * <p>旧 serial 在 Coord 中永久 REVOKED。密钥材料是否删除取决于 Coord 的 key type——本接口不做“丢弃密钥”的承诺。
     */
    IssuedCertificate rotateSigningIdentity(String alias, RevocationReason reason);

    // ==================== 6. TLS ====================

    /**
     * 仅当 {@code identity.keyMaterial()} 是 {@link KeyMaterial.External} 时才可直接构建标准 {@code
     * SSLContext}。
     *
     * <p>如果私钥在 Coord 内部不可导出（{@link KeyMaterial.CoordManaged}）， 标准 JDK 的 {@code KeyManager}
     * 无法使用，必须改用 {@link #sslConfig} 并自行实现委托签名的 {@code KeyManager}。
     *
     * <p>{@code identity == null} 表示匿名客户端：只信任本 PKI 的 CA，不出示身份。
     *
     * @throws IllegalStateException 如果 keyMaterial 是 CoordManaged
     */
    javax.net.ssl.SSLContext sslContext(IssuedCertificate identity);

    /**
     * 返回 TLS 配置描述，不直接构建 SSLContext。 私钥不可导出时，调用方根据 {@link KeyMaterial.CoordManaged#transitKeyName()}
     * 在 {@code KeyManager.engineSign} 中调用 {@link #sign}。
     */
    SslConfig sslConfig(IssuedCertificate identity, ClientAuth clientAuth);

    // ==================== 7. 生命周期 ====================

    PkiInfo info();

    /** 健康检查，不得抛出；无法判定返回 {@link HealthStatus#unknown(String)}。 */
    @Override
    HealthStatus health();

    /** 幂等且不抛。close 后调用其他方法应抛 {@link PkiClosedException}。 */
    @Override
    void close();
}
