package cn.byteforce.seedj.starter.ai.runtime.llm;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 配置（{@code seedj.ai.llm.*}）：starter 自带一个 OpenAI 兼容的 Chat Completions 客户端。
 *
 * <p>默认关闭。开启后 {@code base-url} 与 {@code model} 是启动期必填——没有它们，构造不出一次可解释的请求， 还不如让应用起不来。{@code api-key}
 * 可空（本地 vLLM/Ollama 常常不收鉴权），非空时只进 {@code Authorization} 头。
 *
 * <p>{@code base-url} 是 API 根（例如 {@code https://api.openai.com/v1}），客户端补 {@code
 * /chat/completions}；末尾斜杠会被忽略。
 *
 * @param enabled 是否装配 LLM 客户端，默认 false
 * @param baseUrl API 根地址；{@code enabled=true} 时必填
 * @param apiKey 鉴权密钥；可空，空则不发 {@code Authorization}
 * @param model 模型名；{@code enabled=true} 时必填
 * @param temperature 采样温度；{@code null} 表示不发送，由服务端默认
 * @param maxOutputTokens 输出 token 上限；{@code null} 表示不发送
 * @param timeout 单次请求超时（连接与读取各自使用它），默认 30s
 * @param maxAttempts 单次 {@code next} 的最大尝试次数（含首次），默认 2；只对传输错误、429 与 5xx 重试
 * @param toolChoice 工具选择策略，默认 {@link ToolChoice#REQUIRED}
 * @author ByteForce
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "seedj.ai.llm")
public record LlmProperties(
        @DefaultValue("false") boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxOutputTokens,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("REQUIRED") ToolChoice toolChoice) {

    /** {@code seedj.ai.llm.timeout} 的默认值。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public LlmProperties {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        toolChoice = toolChoice == null ? ToolChoice.REQUIRED : toolChoice;
        if (enabled) {
            requireText(baseUrl, "seedj.ai.llm.base-url");
            requireText(model, "seedj.ai.llm.model");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("seedj.ai.llm.max-attempts 必须 ≥ 1");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("seedj.ai.llm.timeout 必须为正");
        }
    }

    /** 密钥不进日志、不进 toString：配置对象经常被框架打印。 */
    @Override
    public String toString() {
        return "LlmProperties[enabled="
                + enabled
                + ", baseUrl="
                + baseUrl
                + ", apiKey="
                + (isBlank(apiKey) ? "<unset>" : "***")
                + ", model="
                + model
                + ", temperature="
                + temperature
                + ", maxOutputTokens="
                + maxOutputTokens
                + ", timeout="
                + timeout
                + ", maxAttempts="
                + maxAttempts
                + ", toolChoice="
                + toolChoice
                + "]";
    }

    private static void requireText(String value, String key) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(key + " 未配置：seedj.ai.llm.enabled=true 时必须声明");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 工具选择策略的线格式。
     *
     * <p>{@link #REQUIRED} 要求模型必须调用一个工具（端点支持时最可靠）；{@link #AUTO} 允许模型回纯文本—— 但网关仍会拒绝「没有工具调用」的响应，所以
     * {@code AUTO} 只适合端点不支持 {@code required} 的场景。
     */
    public enum ToolChoice {

        /** 由模型决定是否调用工具。 */
        AUTO("auto"),

        /** 必须调用一个工具。 */
        REQUIRED("required");

        private final String wire;

        ToolChoice(String wire) {
            this.wire = wire;
        }

        /**
         * 线格式名称。
         *
         * @return {@code auto} 或 {@code required}
         */
        public String wire() {
            return wire;
        }
    }
}
