package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 决策签名公钥的发布端点：{@code GET /.well-known/agent-decision-jwks.json}。
 *
 * <p>执行侧按 token header 里的 {@code kid} 到这里取公钥；密钥轮换时新旧 {@code kid} 重叠发布，未知 {@code kid}
 * 一律拒绝（fail-closed）。文档只含公钥参数，签名私钥不出签发方（见 {@code Ed25519Jwks}）。
 *
 * <p>本端点只在 {@code seedj.ai.plane.enabled=true} 时装配：执行侧单独部署时不该发布自己不是签发方的公钥。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@RestController
public final class JwksController {

    /** JWKS 文档的固定路径。 */
    public static final String JWKS_PATH = "/.well-known/agent-decision-jwks.json";

    private final DecisionTokenIssuer issuer;

    /**
     * 构造端点。
     *
     * @param issuer 签发方（公钥来源）
     */
    public JwksController(DecisionTokenIssuer issuer) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
    }

    /**
     * 当前密钥集合。
     *
     * @return JWKS 文档（{@code {"keys":[{kty,crv,x,kid,use,alg}]}}）
     */
    @GetMapping(JWKS_PATH)
    public Map<String, Object> jwks() {
        return issuer.jwks().document();
    }
}
