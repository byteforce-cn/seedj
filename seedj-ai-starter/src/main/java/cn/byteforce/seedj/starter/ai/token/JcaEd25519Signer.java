package cn.byteforce.seedj.starter.ai.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.Set;

/**
 * Nimbus {@link JWSSigner} 的 JCA 适配器：只做 Ed25519 运算，签名输入由 Nimbus 组装。
 *
 * <p>{@code JWSObject.sign(JWSSigner)} 会把 {@code BASE64URL(header) + "." + BASE64URL(payload)}
 * 算好后交给这里，因此标准 JWS 签名输入、compact 序列化与 header 形态都不由本类拼装。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class JcaEd25519Signer implements JWSSigner {

    private final PrivateKey privateKey;

    private final JCAContext jcaContext = new JCAContext();

    /**
     * 用 JCA Ed25519 私钥构造签名器。
     *
     * @param privateKey 私钥
     * @throws NullPointerException {@code privateKey} 为 {@code null}
     */
    JcaEd25519Signer(PrivateKey privateKey) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    }

    /**
     * 对 Nimbus 组好的签名输入做 Ed25519 签名。
     *
     * @param header JWS header，必须声明 {@code alg=EdDSA}
     * @param signingInput 标准签名输入
     * @return 签名段
     * @throws JOSEException 算法不符或签名失败
     */
    @Override
    public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
        if (!JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
            throw new JOSEException(
                    "ed25519 signing requires alg=EdDSA, was " + header.getAlgorithm());
        }
        try {
            return Base64URL.encode(Ed25519Keys.sign(privateKey, signingInput));
        } catch (GeneralSecurityException e) {
            throw new JOSEException("ed25519 signing failed", e);
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
