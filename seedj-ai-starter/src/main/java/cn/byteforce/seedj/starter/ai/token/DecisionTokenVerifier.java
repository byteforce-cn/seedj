package cn.byteforce.seedj.starter.ai.token;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.AiErrorCode;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.GuardVerdict;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.starter.ai.policy.PolicyHead;
import cn.byteforce.seedj.starter.ai.policy.PolicyHeadSource;
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
 * 执行侧守卫：把「DecisionToken + 请求侧事实」判成放行或拒绝（实现 spec §7.3 的第 1–8、10 条，外加一条受众校验； 全部 fail-closed）。
 *
 * <p>校验顺序是契约的一部分：先凭据（§7.3-1）、再有效期（§7.3-2）、再身份（§7.3-3）、再命令与哈希（§7.3-4–8），
 * 最后是受众（本实现追加）与幂等键（§7.3-10）。顺序错了会返回错误的错误码，而调用方与告警按码处置。任何一步失败都返回 {@link
 * GuardVerdict.Reject}，本类不抛异常——守卫的失败方式必须是「拒绝」，不是「崩溃后由框架决定」。
 *
 * <p><b>策略新鲜度（spec §7.3 第 9 条，T1-local）</b>：不可逆动作（{@code ActionClass.requiresApproval()}）要求
 * token 里的 {@code engine/bundle/bundleVersion/epoch} 与 {@link PolicyHeadSource} 给出的 head 一致；版本不一致或
 * epoch 不一致返回 {@code policy_stale}，引用缺失、head 缺失、head 有 epoch 而 token 没有返回 {@code
 * policy_unavailable}。{@link PolicyHeadSource#none()}（{@code seedj.ai.policy.verify=none}）表示本部署不参与
 * 校验，本类跳过这一步。可逆与可补偿动作不比对——第 9 条只约束不可逆档位。
 *
 * <p><b>追加校验</b>（都不是 §7.3 的条目，是本实现补的）：
 *
 * <ul>
 *   <li>{@code aud}：防止「token 跨服务复用」，受众口径见下；
 *   <li>{@code typ}：必须是 {@value #TOKEN_TYPE}，否则拒绝——不把别的 JOSE 对象当决策凭据；
 *   <li>长度：单条 token 超过 {@value #TOKEN_MAX_LENGTH} 字节直接拒绝（§7.2 的上限），<b>不截断</b>——截断等于把 一个坏凭据改成另一个坏凭据。
 * </ul>
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

    /** header {@code typ} 的唯一合法值（与 {@link DecisionTokenIssuer} 签发的一致）。 */
    static final String TOKEN_TYPE = "agent-decision+jws";

    /** 单条 token 的长度上限，spec §7.2：超过就不解析，直接拒绝。 */
    static final int TOKEN_MAX_LENGTH = 4096;

    private final Map<String, PublicKey> publicKeys;

    private final ToolRegistry registry;

    private final String audience;

    private final PolicyHeadSource policyHeads;

    /**
     * 用「{@code kid} → 公钥」映射构造守卫（不校验策略新鲜度）。
     *
     * @param publicKeys 已发布公钥，键为 {@code kid}；空映射表示任何 token 都无法验签
     * @param registry 工具注册表：命令类型与动作分类的唯一权威
     * @param audience 本服务名；{@code null} 或空白表示改用 {@link GuardContext#audience()}
     * @throws NullPointerException 任一参数为 {@code null}（{@code audience} 除外）
     */
    public DecisionTokenVerifier(
            Map<String, PublicKey> publicKeys, ToolRegistry registry, String audience) {
        this(publicKeys, registry, audience, PolicyHeadSource.none());
    }

    /**
     * 带策略 head 源的构造器。
     *
     * @param publicKeys 已发布公钥，键为 {@code kid}
     * @param registry 工具注册表
     * @param audience 本服务名；可空
     * @param policyHeads 策略 head 源；{@link PolicyHeadSource#none()} 表示不校验
     * @throws NullPointerException 任一参数为 {@code null}（{@code audience} 除外）
     */
    public DecisionTokenVerifier(
            Map<String, PublicKey> publicKeys,
            ToolRegistry registry,
            String audience,
            PolicyHeadSource policyHeads) {
        Objects.requireNonNull(publicKeys, "publicKeys");
        this.publicKeys = Map.copyOf(publicKeys);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audience = audience;
        this.policyHeads = Objects.requireNonNull(policyHeads, "policyHeads");
    }

    /**
     * 校验一条决策 token。
     *
     * <p>按 spec §7.3 的第 1–8、10 条判定，并在第 8 条之后追加一条受众校验（{@code aud} 不是 §7.3 的条目）； 第 9 条
     * {@code policy_stale} 只对不可逆动作求值，见类说明。
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

        if (jws == null || jws.isBlank()) {
            return reject(AiErrorCode.TOKEN_INVALID, null, "token is missing");
        }
        if (jws.length() > TOKEN_MAX_LENGTH) {
            // 先看长度再解析：超限的串不该进入 JOSE 解析器（不截断，截断会改掉凭据的含义）。
            return reject(
                    AiErrorCode.TOKEN_INVALID,
                    null,
                    "token too large: more than " + TOKEN_MAX_LENGTH + " bytes");
        }

        JWSObject token;
        try {
            token = JWSObject.parse(jws);
        } catch (ParseException | RuntimeException e) {
            return reject(
                    AiErrorCode.TOKEN_INVALID, null, "token is not a JWS compact serialization");
        }

        if (!TOKEN_TYPE.equals(typeOf(token))) {
            return reject(
                    AiErrorCode.TOKEN_INVALID,
                    token.getHeader().getKeyID(),
                    "typ must be " + TOKEN_TYPE + ", was " + typeOf(token));
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
            return reject(
                    AiErrorCode.TOKEN_INVALID, kid, "critical header parameters are not supported");
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
        // tenant 是不透明字符串（例如 "t_07"），与请求侧租户逐字比较，不做 UUID 归一。
        if (!claims.actor().equals(context.actor())
                || !claims.tenant().equals(context.tenantId())
                || claims.onBehalfOf() == null) {
            // 期望 vs 实际都写出来：这两个值都不是秘密，缺了它们运维只能靠猜「哪一侧表示不一样」。
            return reject(
                    AiErrorCode.IDENTITY_MISMATCH,
                    label,
                    "actor/tenant mismatch: expected actor "
                            + claims.actor()
                            + " tenant "
                            + claims.tenant()
                            + ", got actor "
                            + context.actor()
                            + " tenant "
                            + context.tenantId());
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
            return reject(AiErrorCode.ARGS_HASH_MISMATCH, label, "request payload is not hashable");
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

        Optional<GuardVerdict.Reject> stale =
                policyReject(claims.policy(), tool.get().actionClass(), label);
        if (stale.isPresent()) {
            return stale.get();
        }

        if (!claims.decisionId().toString().equals(context.idempotencyKey())) {
            return reject(
                    AiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                    label,
                    "idempotency key must be the decision_id");
        }
        return new GuardVerdict.Admit(claims);
    }

    /**
     * spec §7.3 第 9 条：不可逆动作的策略版本必须与当前 head 一致。
     *
     * <p>返回 {@code Optional.empty()} 表示通过（或本部署不校验）。
     */
    private Optional<GuardVerdict.Reject> policyReject(
            PolicyRef policy, ActionClass actionClass, String label) {
        if (!policyHeads.enforced() || !actionClass.requiresApproval()) {
            return Optional.empty();
        }
        if (policy == null || policy.bundle() == null || policy.bundleVersion() == null) {
            return Optional.of(
                    reject(
                            AiErrorCode.POLICY_UNAVAILABLE,
                            label,
                            "policy reference is missing bundle or bundle_version"));
        }
        Optional<PolicyHead> head = policyHeads.head(policy.engine(), policy.bundle());
        if (head.isEmpty()) {
            return Optional.of(
                    reject(
                            AiErrorCode.POLICY_UNAVAILABLE,
                            label,
                            "no policy head for " + policy.engine() + "/" + policy.bundle()));
        }
        PolicyHead current = head.get();
        if (current.version() != policy.bundleVersion()) {
            return Optional.of(
                    reject(
                            AiErrorCode.POLICY_STALE,
                            label,
                            "policy bundle_version " + policy.bundleVersion()
                                    + " != head " + current.version()));
        }
        if (current.epoch() != null) {
            if (policy.epoch() == null) {
                return Optional.of(
                        reject(
                                AiErrorCode.POLICY_UNAVAILABLE,
                                label,
                                "head provides an epoch but the token does not"));
            }
            if (!current.epoch().equals(policy.epoch())) {
                return Optional.of(
                        reject(AiErrorCode.POLICY_STALE, label, "policy epoch differs from head"));
            }
        }
        return Optional.empty();
    }

    private static GuardVerdict.Reject reject(AiErrorCode code, String label, String detail) {
        String message = label == null ? detail : detail + " (" + label + ")";
        LOG.debug("decision token rejected: code={} detail={}", code.wire(), message);
        return new GuardVerdict.Reject(code, message);
    }

    private static String typeOf(JWSObject token) {
        return token.getHeader().getType() == null ? null : token.getHeader().getType().getType();
    }

    private static String shortId(UUID decisionId) {
        String text = decisionId.toString();
        return text.length() <= SHORT_ID_LENGTH ? text : text.substring(0, SHORT_ID_LENGTH);
    }
}
