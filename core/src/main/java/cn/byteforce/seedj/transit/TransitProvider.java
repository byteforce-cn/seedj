package cn.byteforce.seedj.transit;

/**
 * Transit 业务入口，同时是 provider 实现契约。
 *
 * <p>业务方注入本接口即可，不感知底层是本地内存+磁盘实现还是远程 KMS：
 * <pre>{@code
 * Ciphertext ct = transit.crypto().encrypt(EncryptRequest.of("app-key", "secret"));
 * }</pre>
 *
 * <p>实现必须线程安全、幂等语义见 {@code package-info}。{@code close()} 幂等且不抛；
 * 关闭后除 {@code close()} 外所有方法抛 {@link TransitException}（{@code PROVIDER_CLOSED}）。
 */
public interface TransitProvider extends AutoCloseable {

    TransitCrypto crypto();

    TransitSigner signer();

    TransitMac mac();

    /** 密钥生命周期管理（运维面）。业务方的加解密路径不需要它。 */
    TransitKeyAdmin keyAdmin();

    /** 该 provider 是否支持某种 key 类型上的某种操作。 */
    boolean supports(KeyType keyType, TransitOperation operation);

    @Override
    default void close() {
    }
}
