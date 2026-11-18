package cn.byteforce.seedj.starter.ai.token;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.AiErrorCode;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.GuardVerdict;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行侧守卫：把「DecisionToken + 请求侧事实」判成放行或拒绝（实现 spec §7.3 的第 1–8、10 条，外加一条受众校验；
 * 全部 fail-closed）。
 *
 * <p>校验顺序是契约的一部分：先凭据（§7.3-1）、再有效期（§7.3-2）、再身份（§7.3-3）、再命令与哈希（§7.3-4–8），
 * 最后是受众（本实现追加）与幂等键（§7.3-10）。顺序错了会返回错误的错误码，而调用方与告警按码处置。任何一步失败都返回
 * {@link GuardVerdict.Reject}，本类不抛异常——守卫的失败方式必须是「拒绝」，不是「崩溃后由框架决定」。
 *
 * <p><b>未实现的范围</b>：spec §7.3 第 9 条（{@code policy_stale}：不可逆动作要求 {@code policy.bundle_version}/
 * {@code epoch} 等于策略 head）在 T0 <b>不可求值</b>——{@link GuardContext} 不携带策略 head，T0 不做 {@code
 * Evaluate}（计划 R7），所以本类从不比对 {@link DecisionTokenClaims#policy()}，{@code
 * AiErrorCode.POLICY_STALE} 也恒不被返回。等 U8（{@code Evaluate} 返回求值版本）落地后在 T1 补。
 *
 * <p><b>追加校验</b>：{@code aud} 不是 §7.3 的条目，是本实现为防止「token 跨服务复用」追加的检查；受众口径见下。
 *
 * <p>拒绝说明只带 {@code kid} 与 {@code decision_id} 前 8 位：token 原文不进日志、不进错误响应。
 *
 * <p>受众口径：构造器配置了 {@code audience} 就以它为准，否则取 {@link GuardContext#audience()}——前者是
 * 「本服务是谁」，后者是「请求到了哪个服务」，两者取其一即可判定 {@code aud_mismatch}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DecisionTokenVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(DecisionTokenVerifier.class);

    private static final long CLOCK_SKEW_SECONDS = 60;

    private static final int SHORT_ID_LENGTH = 8;

    private final Map<String, PublicKey> publicKeys;

    private final ToolRegistry registry;

    private final String audience;

    /**
     * 用「{@code kid} → 公钥」映射构造守卫。
     *
     * @param publicKeys 已发布公钥，键为 {@code kid}；空映射表示任何 token 都无法验签
     * @param registry 工具注册表：命令类型与动作分类的唯一权威
     * @param audience 本服务名；{@code null} 或空白表示改用 {@link GuardContext#audience()}
     * @throws NullPointerException 任一参数为 {@code null}（{@code audience} 除外）
     */
    public DecisionTokenVerifier(
            Map<String, PublicKey> publicKeys, ToolRegistry registry, String audience) {
        Objects.requireNonNull(publicKeys, "publicKeys");
        this.publicKeys = Map.copyOf(publicKeys);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audience = audience;
    }

    /**
     * 校验一条决策 token。
     *
     * <p>按 spec §7.3 的第 1–8、10 条判定，并在第 8 条之后追加一条受众校验（{@code aud} 不是 §7.3 的条目）；
     * 第 9 条 {@code policy_stale} 在 T0 不可求值，见类说明。
     *
     * @param jws JWS compact 串（{@code x-agent-decision} 的值）
     * @param context 请求侧事实
     * @param now 当前时间（UTC）；调用方注入以便测试
     * @return 放行（带已校验 claim）或拒绝（带具体错误码）
     * @throws NullPointerException {@code context} 或 {@code now} 为 {@code null}
     */
    public GuardVerdict verify(String jws, GuardContext context, Instant now) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(now, "now");

        JWSObject token;
        try {
            if (jws == null || jws.isBlank()) {
                return reject(AiErrorCode.TOKEN_INVALID, null, "token is missing");
            }
            token = JWSObject.parse(jws);
        } catch (ParseException | RuntimeException e) {
            return reject(AiErrorCode.TOKEN_INVALID, null, "token is not a JWS compact serialization");
        }

        String kid = token.getHeader().getKeyID();
        if (!JWSAlgorithm.EdDSA.equals(token.getHeader().getAlgorithm())) {
            return reject(
                    AiErrorCode.TOKEN_INVALID,
                    kid,
                    "alg must be EdDSA, was " + token.getHeader().getAlgorithm());
        }
        if (token.getHeader().getCriticalParams() != null
                && !token.getHeader().getCriticalParams().isEmpty()) {
            return reject(AiErrorCode.TOKEN_INVALID, kid, "critical header parameters are not supported");
        }
        PublicKey publicKey = kid == null ? null : publicKeys.get(kid);
        if (publicKey == null) {
            return reject(AiErrorCode.TOKEN_INVALID, kid, "unknown kid");
        }
        try {
            JWSVerifier verifier = new JcaEd25519Verifier(publicKey);
            if (!token.verify(verifier)) {
                return reject(AiErrorCode.TOKEN_INVALID, kid, "signature verification failed");
            }
        } catch (JOSEException | RuntimeException e) {
            return reject(AiErrorCode.TOKEN_INVALID, kid, "signature verification failed");
        }

        DecisionTokenClaims claims;
        try {
            claims =
                    DecisionTokenPayload.parse(
                            new String(token.getPayload().toBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return reject(AiErrorCode.TOKEN_INVALID, kid, "invalid claims: " + e.getMessage());
        }
        String label = "kid=" + kid + " decision_id=" + shortId(claims.decisionId());

        Duration ttl = Duration.between(claims.issuedAt(), claims.expiresAt());
        // RFC 7519：exp 是「不得再被接受」的时刻，因此 exp == now 就算过期。
        if (claims.issuedAt().isAfter(now.plusSeconds(CLOCK_SKEW_SECONDS))
                || !claims.expiresAt().isAfter(now)
                || ttl.compareTo(DecisionTokenIssuer.MAX_TTL) > 0) {
            return reject(AiErrorCode.TOKEN_EXPIRED, label, "token is outside its validity window");
        }

        // on_behalf_of 的空值在 core 的 claims 构造器里已被拒绝；这里是对 parse 结果的独立兜底。
        if (!claims.actor().equals(context.actor())
                || !claims.tenant().equals(context.tenantId().toString())
                || claims.onBehalfOf() == null) {
            return reject(AiErrorCode.IDENTITY_MISMATCH, label, "actor/tenant/on_behalf_of mismatch");
        }

        Optional<AgentToolSpec> tool;
        try {
            tool = registry.find(context.commandType());
        } catch (RuntimeException e) {
            return reject(
                    AiErrorCode.COMMAND_NOT_REGISTERED,
                    label,
                    "tool registry lookup failed: " + e.getMessage());
        }
        if (tool.isEmpty()) {
            return reject(
                    AiErrorCode.COMMAND_NOT_REGISTERED,
                    label,
                    "command type not registered: " + context.commandType());
        }
        if (!claims.command().type().equals(context.commandType())) {
            return reject(
                    AiErrorCode.COMMAND_MISMATCH,
                    label,
                    "token command type does not match the request");
        }
        if (!claims.command().targets().equals(context.targets())) {
            return reject(AiErrorCode.TARGETS_MISMATCH, label, "targets differ");
        }
        if (!Objects.equals(claims.command().expectedVersion(), context.expectedVersion())) {
            return reject(AiErrorCode.VERSION_MISMATCH, label, "expectedVersion differs");
        }

        String argsHash;
        try {
            argsHash =
                    ArgsHash.of(
                            new CommandFingerprint(
                                    context.commandType(),
                                    context.payload(),
                                    context.targets(),
                                    context.expectedVersion()));
        } catch (RuntimeException e) {
            return reject(
                    AiErrorCode.ARGS_HASH_MISMATCH, label, "request payload is not hashable");
        }
        if (!claims.argsHash().equals(argsHash)) {
            return reject(AiErrorCode.ARGS_HASH_MISMATCH, label, "args_hash differ");
        }

        if (tool.get().actionClass() != claims.actionClass()) {
            return reject(
                    AiErrorCode.ACTION_CLASS_MISMATCH,
                    label,
                    "registry declares " + tool.get().actionClass().wire());
        }
        if (tool.get().actionClass().requiresApproval()
                && (claims.approval() == null
                        || !claims.approval().argsHash().equals(claims.argsHash()))) {
            return reject(
                    AiErrorCode.APPROVAL_MISSING,
                    label,
                    "approval snapshot is missing or bound to other args");
        }

        String expectedAudience =
                audience == null || audience.isBlank() ? context.audience() : audience;
        if (!claims.audience().equals(expectedAudience)) {
            return reject(AiErrorCode.AUD_MISMATCH, label, "audience differs");
        }

        if (!claims.decisionId().toString().equals(context.idempotencyKey())) {
            return reject(
                    AiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                    label,
                    "idempotency key must be the decision_id");
        }
        return new GuardVerdict.Admit(claims);
    }

    private static GuardVerdict.Reject reject(AiErrorCode code, String label, String detail) {
        String message = label == null ? detail : detail + " (" + label + ")";
        LOG.debug("decision token rejected: code={} detail={}", code.wire(), message);
        return new GuardVerdict.Reject(code, message);
    }

    private static String shortId(UUID decisionId) {
        String text = decisionId.toString();
        return text.length() <= SHORT_ID_LENGTH ? text : text.substring(0, SHORT_ID_LENGTH);
    }
}
