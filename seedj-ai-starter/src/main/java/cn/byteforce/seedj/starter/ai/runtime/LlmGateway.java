package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.ai.Proposal;

/**
 * LLM 端口：给一步的观测，要一个提案。
 *
 * <p>这是 agent 循环里唯一与模型对话的地方。实现方（宿主适配器）只负责「提示词进、提案出」：它不做授权判定，
 * 也不知道契约能力——提案能不能执行由决策面判（见 {@link DecisionClient}）。
 *
 * <p>本接口是宿主必须提供的一个端口：没有它 {@code BoundedLoop} 不装配，starter 不造默认实现（见
 * {@link AiRuntimeAutoConfiguration}）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface LlmGateway {

    /**
     * 推进一步。
     *
     * @param prompt 本步的提示：契约、会话、步序、指令与之前的观测
     * @return 模型提议的下一步
     */
    Proposal next(Prompt prompt);
}
