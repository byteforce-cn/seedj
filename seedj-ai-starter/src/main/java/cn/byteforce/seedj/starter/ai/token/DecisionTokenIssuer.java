package cn.byteforce.seedj.starter.ai.token;

import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.starter.ai.AiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DecisionToken 签发方：把 claim 签成标准 JWS compact 串。
 *
 * <p>header 固定 {@code alg=EdDSA}、{@code typ=agent-decision+jws}、{@code kid}；payload 是 claim 的 审计规范化
 * JSON（见 {@link DecisionTokenPayload}）。签名输入由 Nimbus 的 {@link JWSObject} 组装，本类不拼 {@code
 * BASE64URL(header) + "." + BASE64URL(payload)}。
 *
 * <p><b>密钥</b>：{@code seedj.ai.decision.signing-key} 接受两种编码——base64url 的 32 字节 Ed25519 seed （推荐），或
 * base64/base64url 的 PKCS#8 DER。未配置时在构造期生成临时密钥并打 {@code WARN}：这只让本地开发与 测试开箱可跑，生产必须配置固定密钥，否则重启后旧
 * token 全部无法验证。轮换时新旧 {@code kid} 在 JWKS 里重叠发布。
 *
 * <p><b>实现说明</b>：T0 用 JDK JCA 做 Ed25519 运算 + BouncyCastle 从 seed 推导公钥，因为 Nimbus 的 {@code
 * Ed25519Signer} 依赖未引入的 Tink；wire 形态（header、签名输入、compact 序列化）仍由 Nimbus 保证，未来换 seedj PKI 或换实现不改协议。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DecisionTokenIssuer {

    /** 协议允许的最长有效期（spec §7.2）。 */
    static final Duration MAX_TTL = Duration.ofSeconds(900);

    private static final Logger LOG = LoggerFactory.getLogger(DecisionTokenIssuer.class);

    private static final JOSEObjectType TOKEN_TYPE = new JOSEObjectType("agent-decision+jws");

    private final String kid;

    private final Ed25519Jwks jwks;

    private final JWSSigner signer;

    /**
     * 按配置构造签发方。
     *
     * @param decision {@code seedj.ai.decision} 配置：签名密钥、{@code kid}、TTL 与受众
     * @throws NullPointerException {@code decision} 或其 TTL 为 {@code null}
     * @throws IllegalArgumentException {@code kid} 为空白、TTL 不在 {@code (0s, 900s]}、签名密钥无法解析
     */
    public DecisionTokenIssuer(AiProperties.Decision decision) {
        Objects.requireNonNull(decision, "decision");
        this.kid = requiredKid(decision.kid());
        requireTtl(decision.ttl());
        EdECPrivateKey privateKey = privateKey(decision.signingKey(), kid);
        this.signer = new JcaEd25519Signer(privateKey);
        this.jwks = new Ed25519Jwks(kid, Ed25519Keys.rawPublicKey(privateKey));
    }

    /**
     * 签发一条决策 token。
     *
     * @param claims 已确定的 claim（含 {@code iat}/{@code exp}）
     * @return JWS compact 串（{@code header.payload.signature}）
     * @throws NullPointerException {@code claims} 为 {@code null}
     * @throws IllegalArgumentException claim 有效期不在 {@code (0s, 900s]}：超过 15 分钟的 token 一律签不出来
     */
    public String sign(DecisionTokenClaims claims) {
        Objects.requireNonNull(claims, "claims");
        requireTtl(Duration.between(claims.issuedAt(), claims.expiresAt()));
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.EdDSA).type(TOKEN_TYPE).keyID(kid).build();
        JWSObject jws = new JWSObject(header, new Payload(DecisionTokenPayload.canonical(claims)));
        try {
            jws.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to sign decision token (kid=" + kid + ")", e);
        }
        return jws.serialize();
    }

    /**
     * 本签发方的密钥标识。
     *
     * @return {@code kid}
     */
    public String kid() {
        return kid;
    }

    /**
     * 与签名密钥对应的 JWKS 文档：只含公钥参数。
     *
     * @return JWKS 文档
     */
    public Ed25519Jwks jwks() {
        return jwks;
    }

    private static String requiredKid(String kid) {
        Objects.requireNonNull(kid, "seedj.ai.decision.kid");
        if (kid.isBlank()) {
            throw new IllegalArgumentException("seedj.ai.decision.kid must not be blank");
        }
        return kid;
    }

    private static void requireTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "seedj.ai.decision.ttl");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "seedj.ai.decision.ttl must be within (0s, "
                            + MAX_TTL.toSeconds()
                            + "s], was "
                            + ttl.toSeconds()
                            + "s");
        }
    }

    private static EdECPrivateKey privateKey(String signingKey, String kid) {
        if (signingKey == null || signingKey.isBlank()) {
            LOG.warn(
                    "seedj.ai.decision.signing-key 未配置：已生成临时 Ed25519 密钥（kid={}），"
                            + "仅用于本地/测试，生产必须配置 signing-key",
                    kid);
            return generateEphemeralKey();
        }
        return Ed25519Keys.parsePrivateKey(signingKey);
    }

    private static EdECPrivateKey generateEphemeralKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            return (EdECPrivateKey) keyPair.getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("jdk cannot generate an ed25519 key pair", e);
        }
    }
}
