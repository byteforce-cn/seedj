package cn.byteforce.seedj.provider.pki;

import java.security.PrivateKey;
import java.util.Objects;

/**
 * 已签发证书的密钥归属。
 *
 * <p>两种形态对应两套 TLS 路径：{@link External} 可以直接喂给 JDK 的 {@code KeyManager}；{@link
 * CoordManaged} 只能通过 {@link PkiProvider#sslConfig} + 委托签名使用。
 */
public sealed interface KeyMaterial permits KeyMaterial.CoordManaged, KeyMaterial.External {

    /**
     * 私钥留在 Coord 的命名密钥（Transit 密钥）。
     *
     * @param transitKeyName Transit 密钥名；签名时原样传给 {@link PkiProvider#sign}
     * @param transitKeyVersion 签发时使用的密钥版本；{@code 0} 表示由 Coord 选择最新版本
     */
    record CoordManaged(String transitKeyName, int transitKeyVersion) implements KeyMaterial {

        public CoordManaged {
            if (transitKeyName == null || transitKeyName.isBlank()) {
                throw new IllegalArgumentException("transitKeyName 不能为空");
            }
            if (transitKeyVersion < 0) {
                throw new IllegalArgumentException("transitKeyVersion 不能为负数");
            }
        }

        public CoordManaged(String transitKeyName) {
            this(transitKeyName, 0);
        }
    }

    /**
     * 私钥在调用方手里。
     *
     * <p>{@code privateKey == null} 表示「私钥确实在调用方手里，但没有随 {@link IssuedCertificate}
     * 一起返回」——典型场景是 {@link PkiProvider#signCsr}。调用方用 {@link
     * IssuedCertificate#withPrivateKey} 绑定后再构建 TLS 身份。
     */
    record External(PrivateKey privateKey) implements KeyMaterial {

        public External() {
            this(null);
        }

        public boolean attached() {
            return privateKey != null;
        }

        public PrivateKey requirePrivateKey() {
            return Objects.requireNonNull(privateKey, "external private key is not attached");
        }
    }
}
