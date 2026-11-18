package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.runtime.LlmGateway;
import cn.byteforce.seedj.starter.ai.runtime.Prompt;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化的 LLM 端口：按预置顺序返回提案，让 end-to-end 用例不依赖任何模型。
 *
 * <p>存在的理由是「把不确定性移出被测路径」：{@code BoundedLoop} 的每一步决策、guard 的每次校验、命令的每次落库都要真
 * 实发生，只有「模型会说什么」被固定下来。脚本耗尽仍被要求下一步是错误——静默收尾会把「循环多跑了一步」这类回归藏起来。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ScriptedLlmGateway implements LlmGateway {

    private final Deque<Proposal> script;

    private ScriptedLlmGateway(List<Proposal> script) {
        this.script = new ArrayDeque<>(script);
    }

    /**
     * 按顺序构造脚本。
     *
     * @param script 依次返回的提案
     * @return 网关
     */
    static ScriptedLlmGateway of(Proposal... script) {
        return new ScriptedLlmGateway(List.of(script));
    }

    /**
     * 收尾提案。
     *
     * @return {@code finish} 提案
     */
    static Proposal finish() {
        return new Proposal(DecisionKind.FINISH, null, null, null, "任务完成", List.of());
    }

    /**
     * 取下一步提案。
     *
     * @param prompt 本步提示
     * @return 脚本里的下一个提案
     * @throws IllegalStateException 脚本已耗尽（循环比脚本多走了一步）
     */
    @Override
    public Proposal next(Prompt prompt) {
        Proposal proposal = script.pollFirst();
        if (proposal == null) {
            throw new IllegalStateException(
                    "脚本已耗尽，循环仍要求第 " + prompt.step() + " 步的提案");
        }
        return proposal;
    }
}
