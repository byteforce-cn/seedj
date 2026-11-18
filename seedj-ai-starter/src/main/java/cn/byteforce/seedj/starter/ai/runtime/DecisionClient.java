package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.ai.Proposal;
import java.util.UUID;

/**
 * 决策端口：把 agent 的提案交给决策面求值。
 *
 * <p>{@code deny} 是<b>正常返回</b>，不是异常：决策面已经为它落了 intent 与 outcome(rejected)，调用方只需按 {@link
 * DecisionGrant#allowed()} 分叉。只有传输/协议层面的失败（HTTP 非 2xx、响应无法解析）才抛 {@link
 * IllegalStateException}——那种情况下账本里可能有一条 intent，运行时不能假装「没发生过」。
 *
 * <p>本接口不判权：能不能做由决策面按任务契约能力集决定，runtime 只消费结论（{@link DecisionGrant}）。
 *
 * <p><b>T0 的调用方只会在 {@code EXECUTE_TOOL} 时调用本接口</b>：只读/追问/升级/收尾提案由 {@link BoundedLoop}
 * 在循环内转换，不经过决策面（决策面的 intent 要求 {@code command} 必填，而 {@link Proposal} 只对执行工具携带 {@link
 * cn.byteforce.seedj.ai.CommandRef}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface DecisionClient {

    /**
     * 求值一步。
     *
     * @param contract 任务契约
     * @param step 会话内步序，从 1 开始
     * @param proposal 模型提议的下一步
     * @param audience DecisionToken 的受众；{@code null} 或空白时不发给决策面，由决策面按配置补
     * @return 决策判定：{@code denyRule} 有值为拒绝，否则放行
     * @throws IllegalStateException 决策面返回非 2xx，或响应缺少必要字段
     */
    DecisionGrant ask(TaskContract contract, int step, Proposal proposal, String audience);

    /**
     * 上报一次执行结局：{@code POST /v1/decisions/{decisionId}/outcome}。
     *
     * <p>工具确认执行成功之后调用（{@link ToolResult#ok()} 为 {@code true}）。上报的成功与否不影响已经发生的 副作用，所以 {@link
     * BoundedLoop} 把它当尽力而为的动作：失败只 WARN，不让循环失败。
     *
     * <p>上报的内容只有 {@code command_id}：结局状态、链条目标识都由决策面按链上事实推导，运行时说了不算。
     *
     * @param decisionId 本次执行的决策标识
     * @param commandId 命令面返回的命令标识
     * @throws IllegalStateException 决策面返回非 2xx
     */
    void report(UUID decisionId, UUID commandId);
}
