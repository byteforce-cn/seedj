package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.Proposal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 有界 agent 循环：每一步先问模型，再问决策面，然后才动工具（spec §5.1）。
 *
 * <p><b>顺序是契约</b>：
 *
 * <ol>
 *   <li>{@link LlmGateway#next(Prompt)} —— 一步一个提案；
 *   <li>{@link DecisionClient#ask} —— <b>每一步都问</b>，包括 {@code finish} 与只读步骤（决策面要为它们落
 *       intent + outcome）；
 *   <li>按判定分叉：拒绝即停；{@code finish} 即收尾且不调工具；{@code execute_tool} 才用放行给的 token 调工具；
 *       {@code read} / {@code ask_user} / {@code escalate} 只记一步观测。
 * </ol>
 *
 * <p><b>停止条件之后不再有动作</b>：拒绝、{@code finish}、工具失败、走满上限都立刻返回，不再问决策面、也不再调
 * 工具。上限取 {@code min(contract.maxSteps, 部署上限)}——任务可以比部署更保守，不能更宽松。
 *
 * <p><b>不做授权判定</b>：能不能执行由决策面按契约能力集判，本类只消费 {@link DecisionGrant}。token 只在本类里从
 * 判定传给工具端口，<b>不进观测、不进结局</b>。
 *
 * <p>无状态（网关、决策口、工具口与上限都由外部注入），可单例使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class BoundedLoop {

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
     */
    public LoopResult run(TaskContract contract) {
        Objects.requireNonNull(contract, "contract");
        int maxSteps = Math.min(contract.maxSteps(), stepLimit);
        List<String> observations = new ArrayList<>();
        for (int step = 1; step <= maxSteps; step++) {
            Proposal proposal = gateway.next(prompt(contract, step, observations));
            DecisionGrant grant = decisions.ask(contract, step, proposal, audience);
            if (!grant.allowed()) {
                return LoopResult.denied(step, grant.denyRule(), observations);
            }
            switch (proposal.kind()) {
                case FINISH -> {
                    return LoopResult.finished(step, observations);
                }
                case EXECUTE_TOOL -> {
                    ToolResult result =
                            tools.execute(call(proposal), grant.token(), grant.decisionId());
                    observations.add(describe(step, proposal, result));
                    if (!result.ok()) {
                        return LoopResult.toolFailed(step, result.body(), observations);
                    }
                }
                case READ, ASK_USER, ESCALATE -> observations.add(describe(step, proposal, null));
            }
        }
        return LoopResult.maxStepsReached(maxSteps, observations);
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
     * 生成一行观测：步序、步骤类型，执行工具时再加命令类型、成败与命令标识。
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
