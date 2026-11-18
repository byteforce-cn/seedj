package cn.byteforce.seedj.starter.ai.token;

import com.nimbusds.jose.util.Base64URL;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 决策签名公钥的 JWKS 文档：{@code /.well-known/agent-decision-jwks.json} 的内容。
 *
 * <p>只发布公钥参数：{@code {"keys":[{"kty":"OKP","crv":"Ed25519","x":"…","kid":"…",
 * "use":"sig","alg":"EdDSA"}]}}。{@code kid} 是密钥版本标识，守卫按它选公钥；轮换时新旧 {@code kid} 重叠发布，未知 {@code kid}
 * 一律拒绝（fail-closed）。
 *
 * <p><b>实现说明</b>：T0 用 JDK JCA 做 Ed25519 运算 + BouncyCastle 从 seed 推导公钥，因为 Nimbus 的 {@code
 * Ed25519Signer} 依赖未引入的 Tink；wire 形态（JWK 参数、base64url 无填充）由本类与 Nimbus 的 {@link Base64URL} 保证，未来换
 * PKI 或换实现不改协议。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class Ed25519Jwks {

    private final byte[] rawPublicKey;

    private final Map<String, Object> document;

    /**
     * 用密钥标识与裸公钥构造 JWKS 文档。
     *
     * @param kid 密钥标识
     * @param rawPublicKey {@value Ed25519Keys#KEY_LENGTH} 字节 Ed25519 裸公钥
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException {@code kid} 为空白或公钥长度不符
     */
    public Ed25519Jwks(String kid, byte[] rawPublicKey) {
        Objects.requireNonNull(kid, "kid");
        if (kid.isBlank()) {
            throw new IllegalArgumentException("kid must not be blank");
        }
        Objects.requireNonNull(rawPublicKey, "rawPublicKey");
        if (rawPublicKey.length != Ed25519Keys.KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "ed25519 public key must be "
                            + Ed25519Keys.KEY_LENGTH
                            + " bytes, was "
                            + rawPublicKey.length);
        }
        this.rawPublicKey = rawPublicKey.clone();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "OKP");
        key.put("crv", "Ed25519");
        key.put("x", Base64URL.encode(rawPublicKey).toString());
        key.put("kid", kid);
        key.put("use", "sig");
        key.put("alg", "EdDSA");
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("keys", List.of(Collections.unmodifiableMap(key)));
        this.document = Collections.unmodifiableMap(keys);
    }

    /**
     * JWKS 文档。
     *
     * @return 只含 {@code keys} 的不可变文档，键为 {@code kty}/{@code crv}/{@code x}/{@code kid}/{@code
     *     use}/{@code alg}
     */
    public Map<String, Object> document() {
        return document;
    }

    /**
     * 公钥对象；用于构造验签器。
     *
     * <p>与 {@link #document()} 同源：发布出去的 {@code x} 参数就是这里验签用的公钥，守卫不需要另存一份裸公钥。
     *
     * @return Ed25519 公钥
     */
    public PublicKey publicKey() {
        return Ed25519Keys.publicKey(rawPublicKey);
    }
}
