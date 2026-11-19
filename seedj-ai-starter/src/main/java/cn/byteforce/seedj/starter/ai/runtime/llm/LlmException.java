package cn.byteforce.seedj.starter.ai.runtime.llm;

/**
 * LLM 调用失败：传输错误重试耗尽、非 2xx 响应，或模型输出不符合工具协议。
 *
 * <p>继承 {@link RuntimeException}：{@code LlmGateway.next} 是循环的一步，失败就该让这一步失败（循环与任务 失败），而不是伪造一个 {@code
 * FINISH} 继续跑。异常消息只带状态码与截断后的响应体，绝不带 API key。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class LlmException extends RuntimeException {

    /**
     * 构造失败。
     *
     * @param message 可展示原因
     */
    public LlmException(String message) {
        super(message);
    }

    /**
     * 构造带原因的失败。
     *
     * @param message 可展示原因
     * @param cause 底层错误
     */
    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
