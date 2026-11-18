package cn.byteforce.seedj.starter.ai.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Objects;
import java.util.Set;

/**
 * Nimbus {@link JWSVerifier} 的 JCA 适配器：只做 Ed25519 验签，签名输入由 Nimbus 组装。
 *
 * <p>与 {@link JcaEd25519Signer} 成对：验签输入是 Nimbus 从 compact 序列化还原的 {@code
 * BASE64URL(header) + "." + BASE64URL(payload)}，因此不存在「自己拼字符串」与协议漂移的风险。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class JcaEd25519Verifier implements JWSVerifier {

    private final PublicKey publicKey;

    private final JCAContext jcaContext = new JCAContext();

    /**
     * 用 JCA Ed25519 公钥构造验签器。
     *
     * @param publicKey 公钥
     * @throws NullPointerException {@code publicKey} 为 {@code null}
     */
    JcaEd25519Verifier(PublicKey publicKey) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    /**
     * 校验 Nimbus 组好的签名输入。
     *
     * @param header JWS header，必须声明 {@code alg=EdDSA}
     * @param signingInput 标准签名输入
     * @param signature 签名段
     * @return 验签通过返回 {@code true}
     * @throws JOSEException 算法不符或验签过程出错
     */
    @Override
    public boolean verify(JWSHeader header, byte[] signingInput, Base64URL signature)
            throws JOSEException {
        if (!JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
            throw new JOSEException(
                    "ed25519 verification requires alg=EdDSA, was " + header.getAlgorithm());
        }
        try {
            return Ed25519Keys.verify(publicKey, signingInput, signature.decode());
        } catch (GeneralSecurityException e) {
            throw new JOSEException("ed25519 verification failed", e);
        }
    }

    @Override
    public Set<JWSAlgorithm> supportedJWSAlgorithms() {
        return Set.of(JWSAlgorithm.EdDSA);
    }

    @Override
    public JCAContext getJCAContext() {
        return jcaContext;
    }
}
