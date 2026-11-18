package cn.byteforce.seedj.starter.ai.plane;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentIdentity;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.IdemFingerprint;
import cn.byteforce.seedj.ai.ModelRef;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 决策面的核心：判定契约能力、<b>先落 intent</b>、再决定签不签 token（spec §5.1、§6.4 两段式效果）。
 *
 * <p><b>顺序是契约</b>：
 *
 * <ol>
 *   <li>校验请求（缺字段、未知 {@code kind}、{@code rationale} 超 1KB、命令类型未在工具注册表登记 → 400）；
 *   <li>按 {@link CapabilityRegistry} 判契约能力；
 *   <li>{@link DecisionLedger#appendIntent} —— 返回即已提交，执行侧拿到的每个 token 都能追到一条意图；
 *   <li>deny：追加 {@code outcome(rejected)}，不签 token；
 *   <li>allow + {@code execute_tool}：签发 token。<b>成功时不写 outcome</b>——执行结局由 guard 之后的运行时上报； 签发失败则先追加
 *       {@code outcome(not_issued)} 再抛出（请求仍然 500）；
 *   <li>allow + 其它 {@code kind}：同一调用里写 {@code outcome(not_applicable)}，不签 token。
 * </ol>
 *
 * <p>上述三条是<b>决策面自己的结论</b>，账本里记 {@code source = plane_decided}；只有 runtime 上报与对账才写 {@code
 * runtime_reported} / {@code reconciled}——来源失真会让「这条结局是谁观测到的」无法回答。
 *
 * <p>因此「token 签发失败」不会让意图悬空：intent 已提交，随后追加 {@code outcome(not_issued, signing_failed)}， 请求仍返回
 * 500——不签发就不可能有执行，账本也不会留下「有意图无结局」的洞（spec §6.4(3)）。
 *
 * <p><b>三类拒绝的边界</b>（deny 是决策，未登记是坏请求）：
 *
 * <ul>
 *   <li>命令类型不在 {@link ToolRegistry}：400 {@code invalid_payload}，<b>不写账本</b>；
 *   <li>任务契约不存在：deny，账本 intent + outcome(rejected)， {@code deny_rule} = {@value
 *       #RULE_UNKNOWN_CONTRACT}；
 *   <li>契约存在但命令不在其内：deny，账本 intent + outcome(rejected)， {@code deny_rule} = {@value
 *       #RULE_CAPABILITY}。
 * </ul>
 *
 * <p>第一条是 400 而不是 deny 的理由：spec §6.2 规定 {@code execute_tool} 的 {@code action_class} 必填，
 * 而动作分类只来自工具注册表；拿不到动作分类的提案无法被合法构造成一条 intent。deny 分支要求命令已登记， 正是「deny 是决策、未登记是坏请求」的分界线。
 *
 * <p><b>身份与租户只来自配置</b>（{@code seedj.ai.identity.actor}、{@code on-behalf-of}、 {@code
 * seedj.ai.plane.tenant}）：请求体里的同名字段一律不读。三者在构造期解析为 UUID，缺失或非 UUID 直接让应用启动失败——决策面不接受「匿名写决策」。T0
 * 没有租户上下文注入，因此租户由 {@code seedj.ai.plane.tenant} 钉死。
 *
 * <p><b>参数的两份形态</b>：{@code args_hash} 按请求原文的 {@code {type,payload,targets,expectedVersion}} 服务端重算，
 * 请求里不接受 {@code args_hash}；账本里的 {@code args} 是<b>可展示参数</b>（T0 就是 payload 本身，原文入库在 T0 不做， 将来落对象存储）。
 *
 * <p><b>decision_id</b> 由注入的 {@code IdGenerator<UUID>} 生成，同时是命令的幂等键（{@code idempotency_key} 恒等于它，
 * 请求里不接受）。装配侧优先用宿主的 UUIDv7 生成器（时间有序，便于对账扫描与索引局部性），宿主没有时退化为 {@code UUID.randomUUID()}。
 *
 * <p>非线程安全状态：本类无状态（账本、签发口、时钟都由外部注入），可单例使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class DecisionService {

    /** 响应 {@code verdict} 的线格式取值：放行。 */
    public static final String VERDICT_ALLOW = "allow";

    /** 响应 {@code verdict} 的线格式取值：拒绝。 */
    public static final String VERDICT_DENY = "deny";

    /** {@code deny_rule}：任务契约未在 {@code seedj.ai.contracts[]} 声明。 */
    public static final String RULE_UNKNOWN_CONTRACT = "CONTRACT_UNKNOWN";

    /** {@code deny_rule}：契约存在，但命令不在它的 {@code commands} 内。 */
    public static final String RULE_CAPABILITY = "CONTRACT_CAPABILITY";

    /** 账本 {@code identity.acting_service}：写死为决策面自身，避免被请求影响。 */
    private static final String ACTING_SERVICE = "seedj-ai-plane";

    /** spec §6.2 的 {@code rationale} 上限（UTF-8 字节）；账本是哈希链，不能吃任意大文本。 */
    static final int MAX_RATIONALE_BYTES = 1024;

    /** 签发失败写进 outcome 的 {@code error_code}：没有 token 就不可能有执行。 */
    static final String SIGNING_FAILED = "signing_failed";

    private final CapabilityRegistry capabilities;

    private final ToolRegistry tools;

    private final DecisionLedger ledger;

    private final Signer signer;

    private final IdGenerator<UUID> ids;

    private final Clock clock;

    private final UUID tenantId;

    private final UUID actor;

    private final UUID onBehalfOf;

    private final String audience;

    private final Duration ttl;

    /**
     * 用配置解析出的身份与 token 签发方构造。
     *
     * @param capabilities 任务契约能力集
     * @param tools 工具注册表：动作分类的唯一来源
     * @param ledger 决策账本
     * @param issuer DecisionToken 签发方
     * @param ids 决策标识生成器；{@code decision_id} 同时是外部幂等键，装配侧优先用宿主的 UUIDv7 生成器
     * @param clock 取时入口（{@code iat}/{@code exp} 与 {@code expires_at}）
     * @param properties AI 配置：{@code plane.tenant}、{@code identity.*}、 {@code
     *     decision.ttl/audience}
     * @throws IllegalStateException {@code seedj.ai.plane.tenant} / {@code seedj.ai.identity.actor}
     *     / {@code seedj.ai.identity.on-behalf-of} 缺失或不是 UUID
     */
    public DecisionService(
            CapabilityRegistry capabilities,
            ToolRegistry tools,
            DecisionLedger ledger,
            DecisionTokenIssuer issuer,
            IdGenerator<UUID> ids,
            Clock clock,
            AiProperties properties) {
        this(
                capabilities,
                tools,
                ledger,
                Objects.requireNonNull(issuer, "issuer")::sign,
                ids,
                clock,
                properties);
    }

    /** 签发口：把 {@link DecisionTokenIssuer} 的 final 实现挡在构造器之外，便于钉死「先落 intent」的顺序。 */
    DecisionService(
            CapabilityRegistry capabilities,
            ToolRegistry tools,
            DecisionLedger ledger,
            Signer signer,
            IdGenerator<UUID> ids,
            Clock clock,
            AiProperties properties) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        AiProperties config = Objects.requireNonNull(properties, "properties");
        this.tenantId = requiredTenant(config.plane().tenant());
        this.actor = requiredUuid(config.identity().actor(), "seedj.ai.identity.actor");
        this.onBehalfOf =
                requiredUuid(config.identity().onBehalfOf(), "seedj.ai.identity.on-behalf-of");
        this.audience = blankToNull(config.decision().audience());
        this.ttl = Objects.requireNonNull(config.decision().ttl(), "seedj.ai.decision.ttl");
    }

    /**
     * 求值一条决策请求。
     *
     * @param request 请求体
     * @return 决策结果：deny 只带原因码，allow 的执行工具步骤才带 token
     * @throws NullPointerException {@code request} 为 {@code null}
     * @throws ContractException {@code invalid_payload}：缺必填字段、未知 {@code kind}、 {@code rationale} 超过
     *     {@value #MAX_RATIONALE_BYTES} 字节、命令类型未登记、 {@code execute_tool} 无可用受众
     */
    public DecisionResult decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        UUID taskContractId = required(request.taskContractId(), "task_contract_id");
        UUID conversationId = required(request.conversationId(), "conversation_id");
        int step = required(request.step(), "step");
        DecisionKind kind = kindOf(request.kind());
        Command command = required(request.command(), "command");
        String commandType = text(command.type(), "command.type");
        String rationale = rationale(request.rationale());
        Model model = required(request.model(), "model");
        ModelRef modelRef =
                new ModelRef(
                        text(model.name(), "model.name"),
                        text(model.version(), "model.version"),
                        model.promptVer(),
                        model.paramsDigest());
        List<Evidence> evidence = request.evidence() == null ? List.of() : request.evidence();
        Map<String, Object> payload = command.payload() == null ? Map.of() : command.payload();
        List<UUID> targets = command.targets() == null ? List.of() : List.copyOf(command.targets());

        Optional<AgentToolSpec> tool = tools.find(commandType);
        if (kind == DecisionKind.EXECUTE_TOOL && tool.isEmpty()) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "命令类型未在 seedj.ai.tools 登记: " + commandType);
        }
        String tokenAudience =
                kind == DecisionKind.EXECUTE_TOOL ? audienceOf(request.audience()) : null;
        String denyRule = denyRule(taskContractId, commandType);
        ActionClass actionClass =
                kind == DecisionKind.EXECUTE_TOOL ? tool.get().actionClass() : null;

        CommandFingerprint fingerprint =
                new CommandFingerprint(commandType, payload, targets, command.expectedVersion());
        CommandRef commandRef = new CommandRef(commandType, targets, command.expectedVersion());
        String argsHash = ArgsHash.of(fingerprint);
        UUID decisionId = Objects.requireNonNull(ids.id(), "decision id");
        DecisionIntent intent =
                newIntent(
                        decisionId,
                        taskContractId,
                        conversationId,
                        step,
                        kind,
                        actionClass,
                        commandRef,
                        argsHash,
                        IdemFingerprint.of(fingerprint),
                        // 账本只存“可展示参数”（spec §6.2），而 T0 没有实现字段级脱敏；
                        // 存原文会把 writeOnly 字段写进哈希链账本，所以此处留空，
                        // 指纹信息由 args_hash / idem_fingerprint 承担。
                        Map.of(),
                        rationale,
                        evidence,
                        modelRef);
        DecisionRecord intentRecord = ledger.appendIntent(intent);
        if (denyRule != null) {
            ledger.appendOutcome(
                    decisionId, DecisionEffect.rejected(denyRule), OutcomeSource.PLANE_DECIDED);
            return new DecisionResult(
                    decisionId, intentRecord.recordId(), VERDICT_DENY, denyRule, null, null);
        }
        if (kind != DecisionKind.EXECUTE_TOOL) {
            ledger.appendOutcome(
                    decisionId, DecisionEffect.notApplicable(), OutcomeSource.PLANE_DECIDED);
            return new DecisionResult(
                    decisionId, intentRecord.recordId(), VERDICT_ALLOW, null, null, null);
        }
        Instant issuedAt = clock.now();
        Instant expiresAt = issuedAt.plus(ttl);
        DecisionTokenClaims claims =
                new DecisionTokenClaims(
                        DecisionTokenClaims.VERSION,
                        decisionId,
                        intentRecord.recordId(),
                        commandRef,
                        argsHash,
                        actor,
                        onBehalfOf,
                        tenantId.toString(),
                        actionClass,
                        PolicyRef.none(),
                        null,
                        issuedAt,
                        expiresAt,
                        tokenAudience);
        String token;
        try {
            token = signer.sign(claims);
        } catch (RuntimeException error) {
            ledger.appendOutcome(
                    decisionId,
                    DecisionEffect.notIssued(SIGNING_FAILED),
                    OutcomeSource.PLANE_DECIDED);
            throw error;
        }
        return new DecisionResult(
                decisionId, intentRecord.recordId(), VERDICT_ALLOW, null, token, expiresAt);
    }

    /**
     * 读取一个决策下的全部账本记录（租户作用域由配置的 {@code seedj.ai.plane.tenant} 决定）。
     *
     * @param decisionId 决策标识
     * @return 记录列表，按 {@code sequence} 升序；不存在或不属于本租户时为空列表
     */
    public List<DecisionRecord> records(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        return ledger.byDecision(tenantId, decisionId);
    }

    private DecisionIntent newIntent(
            UUID decisionId,
            UUID taskContractId,
            UUID conversationId,
            int step,
            DecisionKind kind,
            ActionClass actionClass,
            CommandRef command,
            String argsHash,
            String idemFingerprint,
            Map<String, Object> args,
            String rationale,
            List<Evidence> evidence,
            ModelRef model) {
        try {
            return new DecisionIntent(
                    decisionId,
                    tenantId,
                    taskContractId,
                    conversationId,
                    step,
                    kind,
                    actionClass,
                    command,
                    argsHash,
                    idemFingerprint,
                    args,
                    rationale,
                    evidence,
                    model,
                    PolicyRef.none(),
                    new AgentIdentity(actor, onBehalfOf, null, ACTING_SERVICE),
                    null);
        } catch (IllegalArgumentException error) {
            // 只翻译"请求本身不合法"（kind↔action_class 双条件、approval 规则等）。
            // NPE 故意不捕：那是编程错误，必须冒成 500；请求字段缺失已由本类的 required(...)/text(...) 前置拦住。
            String reason = error.getMessage();
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    reason == null ? "invalid decision request" : reason);
        }
    }

    private String denyRule(UUID taskContractId, String commandType) {
        String contractId = taskContractId.toString();
        if (capabilities.find(contractId).isEmpty()) {
            return RULE_UNKNOWN_CONTRACT;
        }
        return capabilities.allows(contractId, commandType) ? null : RULE_CAPABILITY;
    }

    private String audienceOf(String requested) {
        String resolved = blankToNull(requested) == null ? audience : requested;
        if (resolved == null) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "缺少 audience：请求未给出，seedj.ai.decision.audience 也未配置");
        }
        return resolved;
    }

    private static DecisionKind kindOf(String wire) {
        if (wire == null || wire.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少 kind");
        }
        try {
            return DecisionKind.fromWire(wire);
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "未知的 kind: " + wire);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少必填字段 " + field);
        }
        return value;
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "缺少必填字段 " + field);
        }
        return value;
    }

    /** spec §6.2：{@code rationale} 不得为空且不超过 {@value #MAX_RATIONALE_BYTES} 字节（UTF-8）。 */
    private static String rationale(String value) {
        String rationale = text(value, "rationale");
        int bytes = rationale.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_RATIONALE_BYTES) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "rationale 超过上限 " + MAX_RATIONALE_BYTES + " 字节（实际 " + bytes + " 字节）");
        }
        return rationale;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 租户由配置钉死：{@code seedj.ai.plane.tenant} 的 wire 类型就是 UUID，绑定阶段已经拒绝非法值，这里只拒缺失。 */
    private static UUID requiredTenant(UUID tenant) {
        if (tenant == null) {
            throw new IllegalStateException("seedj.ai.plane.tenant 未配置：决策面的租户由配置钉死，不从请求读取");
        }
        return tenant;
    }

    private static UUID requiredUuid(String value, String key) {
        if (blankToNull(value) == null) {
            throw new IllegalStateException(key + " 未配置：决策面的租户与身份由配置钉死，不从请求读取");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(key + " 不是 UUID: " + value);
        }
    }

    /**
     * 签发口：{@code claims -> JWS compact}。
     *
     * <p>只有 {@link DecisionTokenIssuer} 的实现（生产）与抛异常的测试替身（证明 intent 已落账）。放在包里而不是
     * 公开类型：签发方的选择是决策面的内部实现，宿主要换签发方式应该换 {@code DecisionTokenIssuer} bean。
     */
    @FunctionalInterface
    interface Signer {

        /**
         * 签发一条决策 token。
         *
         * @param claims 待签 claim
         * @return JWS compact 串
         */
        String sign(DecisionTokenClaims claims);
    }

    /**
     * 决策请求体（线格式 snake_case）。
     *
     * <p><b>不接受</b> {@code decision_id} / {@code idempotency_key}：前者由服务端生成，后者恒等于前者； 也<b>不接受</b>
     * {@code args_hash}（服务端按 {@code command} 重算）、{@code actor} / {@code on_behalf_of} / {@code
     * tenant}（只来自配置）。这些字段出现在请求里会被 Jackson 忽略。
     *
     * @param taskContractId 任务契约标识
     * @param conversationId 会话标识
     * @param step 会话内步序
     * @param kind 步骤类型线格式名（{@code read} / {@code execute_tool} / ...）
     * @param command 命令引用与参数
     * @param rationale 决策理由（人可读）
     * @param evidence 支撑证据；可空
     * @param model 产生该决策的模型
     * @param audience token 受众；为空时用 {@code seedj.ai.decision.audience}
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionRequest(
            UUID taskContractId,
            UUID conversationId,
            Integer step,
            String kind,
            Command command,
            String rationale,
            List<Evidence> evidence,
            Model model,
            String audience) {}

    /**
     * 请求里的命令：类型、参数原文、目标与期望版本。
     *
     * @param type 命令类型
     * @param payload 参数原文（参与 {@code args_hash}）；为空视为空对象
     * @param targets 目标对象；顺序参与指纹
     * @param expectedVersion 期望版本；{@code null} 表示不校验
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Command(
            String type, Map<String, Object> payload, List<UUID> targets, Long expectedVersion) {}

    /**
     * 请求里的模型引用。
     *
     * @param name 模型名
     * @param version 模型版本
     * @param promptVer 提示词版本；可空
     * @param paramsDigest 采样参数摘要；可空
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Model(String name, String version, String promptVer, String paramsDigest) {}

    /**
     * 决策响应体（线格式 snake_case）。
     *
     * @param decisionId 服务端生成的决策标识（也是命令的幂等键）
     * @param intentRecordId 账本意图记录标识（token 里的 {@code ledger_intent_id}）
     * @param verdict {@value DecisionService#VERDICT_ALLOW} 或 {@value DecisionService#VERDICT_DENY}
     * @param denyRule 拒绝原因码；allow 时为 {@code null}
     * @param token JWS compact 串；非 {@code execute_tool} 的 allow 与 deny 为 {@code null}
     * @param expiresAt token 过期时间；无 token 时为 {@code null}
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DecisionResult(
            UUID decisionId,
            UUID intentRecordId,
            String verdict,
            String denyRule,
            String token,
            Instant expiresAt) {}
}
