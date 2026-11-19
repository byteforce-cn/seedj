/**
 * LLM 适配层：OpenAI 兼容的 Chat Completions + 原生 tool-calling。
 *
 * <p><b>职责</b>：{@link cn.byteforce.seedj.starter.ai.runtime.llm.OpenAiCompatibleLlmGateway} 把
 * {@code Prompt} 渲染成一次 {@code POST {base-url}/chat/completions}，把模型返回的单个 tool call 映射成 {@code
 * Proposal}；{@link cn.byteforce.seedj.starter.ai.runtime.llm.LlmProperties} 是全部 服务端配置。装配在 {@link
 * cn.byteforce.seedj.starter.ai.runtime.llm.LlmAutoConfiguration}，与 {@code ai.runtime}
 * 的循环装配分开登记，避免包环。
 *
 * <p><b>边界</b>：不判权（决策面做）、不写账本、不执行工具。`execute_command` 的 `action_class` 来自 {@code
 * seedj.ai.tools[]}，不读模型给的值。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.starter.ai.runtime.llm;
