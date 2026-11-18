package cn.byteforce.seedj.starter.ai;

import cn.byteforce.seedj.ai.ActionClass;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 能力配置（{@code seedj.ai.*}）。
 *
 * <p>开关分四层：{@code enabled} 是总闸，{@code plane}/{@code guard}/{@code runtime} 是三个角色开关——生产上决策面与
 * 执行侧用同一份 starter、不同开关分角色。标量默认值用 {@link DefaultValue} 声明（构造器绑定下「未配置」与「显式
 * false/0」必须区分），整组未配置时由紧凑构造器补默认实例，空绑定即可启动。
 *
 * <p>本类只做绑定；{@code tools[].commandType} / {@code tools[].actionClass} 的必填与存在性校验由使用方（工具注册表）
 * 在启动期完成。
 *
 * @param enabled 总开关，默认 true；false 时连启用标记都不产
 * @param plane 决策面配置
 * @param guard 执行侧 guard 配置
 * @param runtime agent 循环配置
 * @param identity 决策身份配置
 * @param decision DecisionToken 配置
 * @param tools 允许 agent 调用的命令清单
 * @param contracts 任务契约清单
 */
@ConfigurationProperties(prefix = "seedj.ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        Plane plane,
        Guard guard,
        Runtime runtime,
        Identity identity,
        Decision decision,
        List<Tool> tools,
        List<Contract> contracts) {

    /** {@code plane.reconcileTtl} 默认值。 */
    public static final Duration DEFAULT_RECONCILE_TTL = Duration.ofMinutes(10);

    /** {@code runtime.maxSteps} 默认值；T0 上限是有界的 agent 循环。 */
    public static final int DEFAULT_MAX_STEPS = 6;

    /** {@code decision.kid} 默认值：T0 只跑一把内置开发密钥。 */
    public static final String DEFAULT_KID = "agent-decision-2026-11";

    /** {@code decision.ttl} 默认值。 */
    public static final Duration DEFAULT_DECISION_TTL = Duration.ofSeconds(300);

    public AiProperties {
        plane = plane == null ? new Plane(false, null, null, DEFAULT_RECONCILE_TTL) : plane;
        guard = guard == null ? new Guard(true) : guard;
        runtime = runtime == null ? new Runtime(false, DEFAULT_MAX_STEPS) : runtime;
        identity = identity == null ? new Identity(null, null) : identity;
        decision =
                decision == null
                        ? new Decision(null, DEFAULT_KID, DEFAULT_DECISION_TTL, null)
                        : decision;
        tools = tools == null ? List.of() : List.copyOf(tools);
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }

    /**
     * 决策面配置：intent 先行、只读不签 token。
     *
     * <p>{@code tenant} 是决策面唯一的租户来源：T0 没有租户上下文注入，而账本按租户分区、读记录也按租户过滤，
     * 所以它必须在配置里钉死（缺 UUID 即启动失败）。它也是 {@code identity} 只来自配置的同一条思路——
     * 决策面不接受请求自称的租户与主体。
     *
     * @param enabled 是否装配决策面，默认 false（执行侧不需要它）
     * @param tenant 决策面所属租户（必填 UUID）；账本分区 {@code t_<tenant>} 与 token 的 {@code tenant} claim 都用它
     * @param reconcile 对账配置（数据源 bean 名与定时开关）；未配置时给空实例
     * @param reconcileTtl 对账时间窗（{@code seedj.ai.plane.reconcile-ttl}），默认 10m
     */
    public record Plane(
            @DefaultValue("false") boolean enabled,
            UUID tenant,
            Reconcile reconcile,
            @DefaultValue("10m") Duration reconcileTtl) {

        public Plane {
            reconcile = reconcile == null ? new Reconcile(null, false) : reconcile;
        }
    }

    /**
     * 对账配置：键路径是 {@code seedj.ai.plane.reconcile.*}。
     *
     * <p>开关与数据源放在同一个分组下，因为它们是同一件事的两面：打开定时对账，就得知道去哪个业务库读链上事实。
     *
     * @param datasource 对账用的数据源 bean 名称；空表示用主数据源
     * @param enabled 是否装配定时对账，默认 false（宿主还需自己写 {@code @EnableScheduling}）
     */
    public record Reconcile(
            String datasource, @DefaultValue("false") boolean enabled) {}

    /**
     * 执行侧 guard 配置。
     *
     * @param enabled 是否装配 guard，默认 true：执行侧默认拒绝无 token 的写命令
     */
    public record Guard(@DefaultValue("true") boolean enabled) {}

    /**
     * 有界 agent 循环配置。
     *
     * @param enabled 是否装配 agent 循环，默认 false
     * @param maxSteps 单次任务的工具调用步数上限，默认 6
     */
    public record Runtime(
            @DefaultValue("false") boolean enabled, @DefaultValue("6") int maxSteps) {}

    /**
     * 决策身份配置：身份只来自配置，不取请求头。
     *
     * <p>缺省语义按角色不同：guard/verifier 侧 {@code actor} 空表示把调用方当作匿名 agent、{@code onBehalfOf}
     * 空表示 agent 自主决策；{@code seedj.ai.plane.enabled=true} 时两者都是<b>必填 UUID</b>——决策面不接受
     * 「匿名写决策」，缺失或非 UUID 直接启动失败。
     *
     * @param actor agent 自身身份（{@code seedj.ai.identity.actor}）；决策面必填
     * @param onBehalfOf 代办的自然人身份（{@code seedj.ai.identity.on-behalf-of}）；决策面必填
     */
    public record Identity(String actor, String onBehalfOf) {}

    /**
     * DecisionToken 配置。
     *
     * @param signingKey Ed25519 私钥：base64url 的 32 字节 seed（推荐）或 base64/base64url 的 PKCS#8 DER；空表示生成本地
     *     临时密钥（仅供本地/测试，生产必须配置）
     * @param kid 密钥标识，默认 {@link #DEFAULT_KID}
     * @param ttl 令牌有效期，默认 300s
     * @param audience 校验方期望的 audience；guard 侧空表示不限，决策面要求「请求 audience 或本键至少一个」，
     *     两者都缺时那条 {@code execute_tool} 请求 400（{@code seedj.ai.decision.audience}）
     */
    public record Decision(
            String signingKey,
            @DefaultValue("agent-decision-2026-11") String kid,
            @DefaultValue("300s") Duration ttl,
            String audience) {}

    /**
     * 允许 agent 调用的命令。
     *
     * @param commandType 命令类型，必须存在于命令目录
     * @param actionClass 命令的动作分类，guard 按它决定审批强度
     */
    public record Tool(String commandType, ActionClass actionClass) {}

    /**
     * 任务契约：把一组命令归给一个能力集，决策面据此判越权。
     *
     * @param id 契约标识
     * @param commands 契约允许的命令类型
     */
    public record Contract(String id, List<String> commands) {

        public Contract {
            commands = commands == null ? List.of() : List.copyOf(commands);
        }
    }
}
