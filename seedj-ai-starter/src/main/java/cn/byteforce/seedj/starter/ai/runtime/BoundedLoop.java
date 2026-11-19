package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.Proposal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 有界 agent 循环：每一步先问模型，再按提案类型分叉（spec §5.1）。
 *
 * <p><b>T0 只对写动作走决策面</b>：
 *
 * <ol>
 *   <li>{@link LlmGateway#next(Prompt)} —— 一步一个提案；
 *   <li>{@code FINISH} 收尾，不调决策面、不调工具；
 *   <li>{@code EXECUTE_TOOL} 才 {@link DecisionClient#ask}：拒绝即停，放行才用发放的 token 调工具； 工具确认执行后再 {@link
 *       DecisionClient#report} 上报 outcome；
 *   <li>{@code READ} / {@code ASK_USER} / {@code ESCALATE} 是<b>循环内转换</b>（记一行观测，下一步由模型接着决定），
 *       不调决策面、不落账。
 * </ol>
 *
 * <p><b>为什么只读/终结步骤不问决策面</b>：决策面的 intent 要求 {@code command} 必填，而 {@link Proposal} 只在 {@code
 * EXECUTE_TOOL} 时携带 {@link CommandRef}——拿只读/终结提案去问决策面，只会拿到 400（或一条能力集 deny），
 * 账本上留下的也不是一条可解释的决策。等价的只读事实由运行时的观测承载；决策面的只读路径仍可由宿主直接调用 {@link HttpDecisionClient}（组合根自己带 {@code
 * command} 时）。这条取舍是 T0 的范围收窄，不是「只读不需要决策」。
 *
 * <p><b>停止条件之后不再有动作</b>：拒绝、{@code finish}、工具失败、走满上限都立刻返回，不再问决策面、也不再调 工具。上限取 {@code
 * min(contract.maxSteps, 部署上限)}——任务可以比部署更保守，不能更宽松。
 *
 * <p><b>outcome 上报是尽力而为</b>：工具已返回 {@code ok}，副作用已经发生，上报失败不能让循环失败（那会抹掉 一次成功执行的观察）。失败只打一条 WARN（带
 * {@code decision_id}，不带 token），循环照常往下走。后果是那个决策在账本 上停在「有 intent 无 outcome」：T0 的对账入口（{@code
 * PlaneReconciliationSource} 默认返回空）无法自动修补。
 *
 * <p><b>不做授权判定</b>：能不能执行由决策面按契约能力集判，本类只消费 {@link DecisionGrant}。本类不写入 token： 它只把 token
 * 从判定传给工具端口；{@link LoopResult#detail()} 是工具返回体原文（外部文本，截断到 {@value LoopResult#MAX_DETAIL_CHARS}
 * 字符），观测里只有步骤描述。
 *
 * <p>无状态（网关、决策口、工具口与上限都由外部注入），可单例使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class BoundedLoop {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedLoop.class);

    private final LlmGateway gateway;

    private final DecisionClient decisions;

    private final ToolInvoker tools;

    private final int stepLimit;

    private final String audience;

    /**
     * 构造循环。
     *
     * @param gateway LLM 端口
     * @param decisions 决策端口
     * @param tools 工具端口
     * @param stepLimit 部署的步数上限（{@code seedj.ai.runtime.max-steps}），至少 1
     * @param audience DecisionToken 的受众（{@code seedj.ai.decision.audience}）；可空
     * @throws NullPointerException {@code gateway}、{@code decisions} 或 {@code tools} 为 {@code null}
     * @throws IllegalArgumentException {@code stepLimit} 小于 1
     */
    public BoundedLoop(
            LlmGateway gateway,
            DecisionClient decisions,
            ToolInvoker tools,
            int stepLimit,
            String audience) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.tools = Objects.requireNonNull(tools, "tools");
        if (stepLimit < 1) {
            throw new IllegalArgumentException("stepLimit must be positive");
        }
        this.stepLimit = stepLimit;
        this.audience = audience;
    }

    /**
     * 跑完一次任务。
     *
     * @param contract 任务契约（含本次任务的步数上限）
     * @return 结局：收尾、被拒、工具失败或走满上限
     * @throws NullPointerException {@code contract} 为 {@code null}
     * @throws IllegalArgumentException {@code EXECUTE_TOOL} 提案缺少 {@code rationale}（决策面必填，本地先拦）
     */
    public LoopResult run(TaskContract contract) {
        Objects.requireNonNull(contract, "contract");
        int maxSteps = Math.min(contract.maxSteps(), stepLimit);
        List<String> observations = new ArrayList<>();
        for (int step = 1; step <= maxSteps; step++) {
            Proposal proposal = gateway.next(prompt(contract, step, observations));
            switch (proposal.kind()) {
                case FINISH -> {
                    return LoopResult.finished(step, observations);
                }
                case EXECUTE_TOOL -> {
                    requireRationale(proposal);
                    DecisionOutcome outcome =
                            decisions.askOutcome(contract, step, proposal, audience);
                    if (outcome instanceof DecisionOutcome.Denied denied) {
                        return LoopResult.denied(step, denied.grant().denyRule(), observations);
                    }
                    if (outcome instanceof DecisionOutcome.Pending pending) {
                        return LoopResult.pendingApproval(
                                step, pending.pending().decisionId(), observations);
                    }
                    DecisionGrant grant = ((DecisionOutcome.Granted) outcome).grant();
                    ToolResult result =
                            tools.execute(call(proposal), grant.token(), grant.decisionId());
                    observations.add(describe(step, proposal, result));
                    if (!result.ok()) {
                        return LoopResult.toolFailed(step, result.body(), observations);
                    }
                    reportOutcome(grant.decisionId(), result.commandId());
                }
                case READ, ASK_USER, ESCALATE -> observations.add(describe(step, proposal, null));
            }
        }
        return LoopResult.maxStepsReached(maxSteps, observations);
    }

    /**
     * 上报一次执行结局；失败只 WARN——命令已经执行成功，上报失败不能把整个循环算成失败。
     *
     * <p>日志里只有 {@code decision_id}：token 从不进日志。没有 {@code command_id} 时无法构造可解释的上报，直接跳过。
     */
    private void reportOutcome(UUID decisionId, UUID commandId) {
        if (commandId == null) {
            LOG.warn("outcome report skipped for decision {}: command id is missing", decisionId);
            return;
        }
        try {
            decisions.report(decisionId, commandId);
        } catch (RuntimeException error) {
            LOG.warn("outcome report failed for decision {}", decisionId, error);
        }
    }

    private static Prompt prompt(TaskContract contract, int step, List<String> observations) {
        return new Prompt(
                contract.id(),
                contract.conversationId(),
                step,
                contract.instructions(),
                List.copyOf(observations));
    }

    private static Call call(Proposal proposal) {
        CommandRef command = proposal.command();
        return new Call(
                command.type(), proposal.payload(), command.targets(), command.expectedVersion());
    }

    /**
     * 决策面要求 {@code rationale} 必填（≤1KB）：缺了就是一趟注定 400 的请求，本地先拦并点名原因。
     *
     * @throws IllegalArgumentException {@code rationale} 为 {@code null} 或空白
     */
    private static void requireRationale(Proposal proposal) {
        if (proposal.rationale() == null || proposal.rationale().isBlank()) {
            throw new IllegalArgumentException("EXECUTE_TOOL 提议必须带 rationale：决策面要求 rationale");
        }
    }

    /**
     * 生成一行观测：步序、步骤类型；执行工具时再加命令类型、成败与命令标识。
     *
     * <p>观测会进下一轮的提示与日志，因此只放可展示事实：token 从不出现。
     */
    private static String describe(int step, Proposal proposal, ToolResult result) {
        StringBuilder line =
                new StringBuilder("step ").append(step).append(' ').append(proposal.kind().wire());
        if (result != null && proposal.command() != null) {
            line.append(' ')
                    .append(proposal.command().type())
                    .append(' ')
                    .append(result.ok() ? "ok" : "failed")
                    .append(" command ")
                    .append(result.commandId() == null ? "unknown" : result.commandId());
        }
        return line.toString();
    }
}
