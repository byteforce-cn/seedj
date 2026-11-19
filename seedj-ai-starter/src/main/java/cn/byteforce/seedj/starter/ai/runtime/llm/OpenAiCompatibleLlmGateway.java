package cn.byteforce.seedj.starter.ai.runtime.llm;

import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.runtime.LlmGateway;
import cn.byteforce.seedj.starter.ai.runtime.Prompt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容的 LLM 客户端：一次请求一个 tool call，映射成 {@link Proposal}。
 *
 * <p><b>工具协议</b>：请求里带一个 {@code execute_command}（{@code command_type} 是 {@code seedj.ai.tools[]}
 * 的枚举，按任务契约过滤）与三个终结工具 {@code finish} / {@code ask_user} / {@code escalate}。{@code action_class}
 * 来自配置，不读模型给的字段——否则模型能把自己降成 {@code READ_ONLY}。
 *
 * <p><b>fail-closed</b>：没有工具调用、一次多个工具调用、未知工具/命令、参数不是 JSON、{@code execute_command} 缺 {@code
 * rationale} 或超过决策面的 1KB 上限、{@code targets} 不是 UUID —— 一律抛 {@link LlmException}。网关不猜、不把坏输出降级成
 * {@code finish}：一次错误的收尾比一次失败更危险。
 *
 * <p><b>重试</b>只覆盖传输错误、429 与 5xx，次数由 {@code seedj.ai.llm.max-attempts} 控制；4xx 立即失败。 API key 只进
 * {@code Authorization}，不进日志与异常消息。
 *
 * <p>无状态，可单例使用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class OpenAiCompatibleLlmGateway implements LlmGateway {

    /** Chat Completions 路径，拼在 {@code base-url} 之后。 */
    static final String CHAT_PATH = "/chat/completions";

    /** 错误响应体进入异常消息时的截断长度。 */
    static final int MAX_ERROR_BODY = 512;

    /** 决策面对 {@code rationale} 的上限（UTF-8 字节），网关本地先拦。 */
    static final int MAX_RATIONALE_BYTES = 1024;

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleLlmGateway.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

    private static final String SYSTEM_PROMPT =
            """
            你是 seedj agent 的一步执行器。每一轮只调用一个工具，不要输出解释性正文。
            - execute_command：执行一个已登记命令。command_type 只能取工具参数枚举里的值；
              payload 是命令载荷对象；rationale 说明为什么这一步能推进任务。
            - finish：任务完成，reason 给出结论。
            - ask_user：缺少必要信息，reason 写明要问什么。
            - escalate：需要人工处理，reason 说明原因。
            不要编造命令类型，不要把决策或授权判断写进载荷。
            """;

    private final RestClient client;

    private final String baseUrl;

    private final String apiKey;

    private final String model;

    private final Double temperature;

    private final Integer maxOutputTokens;

    private final int maxAttempts;

    private final String toolChoice;

    private final Map<String, AgentToolSpec> tools;

    private final Map<String, Set<String>> contractCommands;

    /**
     * 按配置与工具清单构造。
     *
     * @param builder 宿主的 HTTP 客户端构造器（超时由装配方设置）
     * @param properties LLM 配置
     * @param ai AI 配置：{@code tools[]} 是工具白名单，{@code contracts[]} 按任务契约过滤
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException 地址不是 http(s)，或工具清单缺字段
     */
    public OpenAiCompatibleLlmGateway(
            RestClient.Builder builder, LlmProperties properties, AiProperties ai) {
        LlmProperties config = Objects.requireNonNull(properties, "properties");
        AiProperties aiProperties = Objects.requireNonNull(ai, "ai");
        this.client = Objects.requireNonNull(builder, "builder").build();
        this.baseUrl = normalizeBaseUrl(config.baseUrl());
        this.apiKey = config.apiKey();
        this.model = config.model();
        this.temperature = config.temperature();
        this.maxOutputTokens = config.maxOutputTokens();
        this.maxAttempts = config.maxAttempts();
        this.toolChoice = config.toolChoice().wire();
        this.tools = catalog(aiProperties.tools());
        this.contractCommands = contracts(aiProperties.contracts());
    }

    /**
     * 推进一步。
     *
     * @param prompt 本步提示
     * @return 模型提议的下一步
     * @throws LlmException 调用失败或输出不符合工具协议
     */
    @Override
    public Proposal next(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        Map<String, AgentToolSpec> allowed = allowedTools(prompt);
        ResponseEntity<String> response = post(requestBody(prompt, allowed));
        return parse(response.getBody(), allowed);
    }

    private Map<String, Object> requestBody(Prompt prompt, Map<String, AgentToolSpec> allowed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userContent(prompt))));
        body.put("tools", functions(allowed.keySet()));
        body.put("tool_choice", toolChoice);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxOutputTokens != null) {
            body.put("max_tokens", maxOutputTokens);
        }
        return body;
    }

    private static String userContent(Prompt prompt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("task_contract_id", prompt.taskContractId());
        content.put("conversation_id", prompt.conversationId().toString());
        content.put("step", prompt.step());
        content.put("instructions", prompt.instructions());
        content.put("observations", prompt.observations());
        return JSON.writeValueAsString(content);
    }

    private ResponseEntity<String> post(Map<String, Object> body) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response =
                        client.post()
                                .uri(baseUrl + CHAT_PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .headers(
                                        headers -> {
                                            if (apiKey != null && !apiKey.isBlank()) {
                                                headers.setBearerAuth(apiKey);
                                            }
                                        })
                                .body(body)
                                .retrieve()
                                .onStatus(status -> true, (request, errorResponse) -> {})
                                .toEntity(String.class);
                int status = response.getStatusCode().value();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (retryable(status) && attempt < maxAttempts) {
                    LOG.warn(
                            "llm request failed with status {} (attempt {}), retrying",
                            status,
                            attempt);
                    continue;
                }
                throw new LlmException(
                        "LLM 返回 "
                                + status
                                + "（第 "
                                + attempt
                                + " 次尝试）："
                                + truncate(response.getBody()));
            } catch (RestClientException error) {
                if (attempt >= maxAttempts) {
                    throw new LlmException("LLM 请求失败（传输层，已尝试 " + attempt + " 次）", error);
                }
                LOG.warn("llm transport failure (attempt {}): {}", attempt, error.getMessage());
            }
        }
        throw new IllegalStateException("unreachable: retry loop exhausted without a result");
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private Proposal parse(String body, Map<String, AgentToolSpec> allowed) {
        if (body == null || body.isBlank()) {
            throw new LlmException("LLM 返回空响应体");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException error) {
            throw new LlmException("LLM 响应不是 JSON：" + truncate(body), error);
        }
        JsonNode calls = toolCalls(root);
        if (calls == null || !calls.isArray() || calls.size() == 0) {
            throw new LlmException("模型没有返回工具调用：拒绝把纯文本当成下一步");
        }
        if (calls.size() > 1) {
            throw new LlmException("一次只接受一个工具调用，实际 " + calls.size() + " 个");
        }
        JsonNode function = calls.get(0).get("function");
        if (function == null || !function.isObject()) {
            throw new LlmException("工具调用缺少 function 对象");
        }
        String name = requiredText(function, "name");
        JsonNode arguments = arguments(function);
        return switch (name) {
            case "execute_command" -> execute(arguments, allowed);
            case "finish" -> terminal(DecisionKind.FINISH, arguments);
            case "ask_user" -> terminal(DecisionKind.ASK_USER, arguments);
            case "escalate" -> terminal(DecisionKind.ESCALATE, arguments);
            default -> throw new LlmException("未知工具：" + name);
        };
    }

    private static JsonNode toolCalls(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            throw new LlmException("LLM 响应缺少 choices");
        }
        JsonNode message = choices.get(0).get("message");
        return message == null ? null : message.get("tool_calls");
    }

    private static JsonNode arguments(JsonNode function) {
        JsonNode arguments = function.get("arguments");
        if (arguments == null || arguments.isNull()) {
            return JSON.createObjectNode();
        }
        if (arguments.isObject()) {
            return arguments;
        }
        if (!arguments.isString()) {
            throw new LlmException("工具参数必须是 JSON 字符串或对象");
        }
        String text = arguments.stringValue();
        if (text.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(text);
        } catch (RuntimeException error) {
            throw new LlmException("工具参数不是合法 JSON：" + truncate(text), error);
        }
    }

    private static Proposal execute(JsonNode arguments, Map<String, AgentToolSpec> allowed) {
        if (!arguments.isObject()) {
            throw new LlmException("execute_command 的参数必须是 JSON 对象");
        }
        String commandType = requiredText(arguments, "command_type");
        AgentToolSpec spec = allowed.get(commandType);
        if (spec == null) {
            throw new LlmException("命令不在契约/工具白名单内：" + commandType);
        }
        Map<String, Object> payload = payload(arguments);
        String rationale = rationale(arguments);
        List<UUID> targets = targets(arguments);
        Long expectedVersion = optionalLong(arguments, "expected_version");
        return new Proposal(
                DecisionKind.EXECUTE_TOOL,
                spec.actionClass(),
                new CommandRef(commandType, targets, expectedVersion),
                payload,
                rationale,
                List.of());
    }

    private static Map<String, Object> payload(JsonNode arguments) {
        JsonNode payload = arguments.get("payload");
        if (payload == null || payload.isNull()) {
            return Map.of();
        }
        if (!payload.isObject()) {
            throw new LlmException("execute_command 的 payload 必须是 JSON 对象");
        }
        return JSON.convertValue(payload, PAYLOAD);
    }

    private static String rationale(JsonNode arguments) {
        String rationale = requiredText(arguments, "rationale");
        if (rationale.isBlank()) {
            throw new LlmException("execute_command 必须带非空 rationale");
        }
        int bytes = rationale.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_RATIONALE_BYTES) {
            throw new LlmException("rationale 超过 " + MAX_RATIONALE_BYTES + " 字节（实际 " + bytes + "）");
        }
        return rationale;
    }

    private static List<UUID> targets(JsonNode arguments) {
        JsonNode targets = arguments.get("targets");
        if (targets == null || targets.isNull()) {
            return List.of();
        }
        if (!targets.isArray()) {
            throw new LlmException("execute_command 的 targets 必须是数组");
        }
        List<UUID> parsed = new ArrayList<>();
        for (JsonNode target : targets) {
            if (!target.isString()) {
                throw new LlmException("targets 只能包含 UUID 字符串");
            }
            try {
                parsed.add(UUID.fromString(target.stringValue()));
            } catch (IllegalArgumentException error) {
                throw new LlmException("targets 不是合法 UUID：" + target.stringValue(), error);
            }
        }
        return List.copyOf(parsed);
    }

    private static Proposal terminal(DecisionKind kind, JsonNode arguments) {
        JsonNode reason = arguments.get("reason");
        String text =
                reason == null || reason.isNull() || !reason.isString()
                        ? null
                        : reason.stringValue();
        return new Proposal(kind, null, null, null, text, List.of());
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isString()) {
            throw new LlmException("缺少字符串字段：" + name);
        }
        return value.stringValue();
    }

    private static Long optionalLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new LlmException(name + " 必须是整数");
        }
        return value.longValue();
    }

    private Map<String, AgentToolSpec> allowedTools(Prompt prompt) {
        Set<String> contract = contractCommands.get(prompt.taskContractId());
        if (contract == null) {
            return tools;
        }
        Map<String, AgentToolSpec> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, AgentToolSpec> entry : tools.entrySet()) {
            if (contract.contains(entry.getKey())) {
                allowed.put(entry.getKey(), entry.getValue());
            }
        }
        return allowed;
    }

    private static List<Map<String, Object>> functions(Set<String> commandTypes) {
        List<Map<String, Object>> functions = new ArrayList<>();
        if (!commandTypes.isEmpty()) {
            functions.add(executeFunction(commandTypes));
        }
        functions.add(terminalFunction("finish", "任务完成，reason 给出结论"));
        functions.add(terminalFunction("ask_user", "缺少必要信息，reason 写明要问什么"));
        functions.add(terminalFunction("escalate", "需要人工处理，reason 说明原因"));
        return List.copyOf(functions);
    }

    private static Map<String, Object> executeFunction(Set<String> commandTypes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "command_type",
                Map.of(
                        "type",
                        "string",
                        "enum",
                        List.copyOf(commandTypes),
                        "description",
                        "已登记的命令类型，只能取枚举值"));
        properties.put(
                "payload",
                Map.of(
                        "type",
                        "object",
                        "description",
                        "命令载荷；字段由命令自身校验",
                        "additionalProperties",
                        true));
        properties.put(
                "rationale", Map.of("type", "string", "description", "为什么这一步能推进任务（必填，≤1KB）"));
        properties.put(
                "targets",
                Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "目标对象 UUID；可省略"));
        properties.put("expected_version", Map.of("type", "integer", "description", "期望对象版本；可省略"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("command_type", "payload", "rationale"));
        return function("execute_command", "执行一个已登记命令", parameters);
    }

    private static Map<String, Object> terminalFunction(String name, String description) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put(
                "properties",
                Map.of("reason", Map.of("type", "string", "description", description)));
        return function(name, description, parameters);
    }

    private static Map<String, Object> function(
            String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);
        return Map.of("type", "function", "function", function);
    }

    private static Map<String, AgentToolSpec> catalog(List<AiProperties.Tool> tools) {
        Map<String, AgentToolSpec> catalog = new LinkedHashMap<>();
        for (AiProperties.Tool tool : tools) {
            if (tool == null || tool.commandType() == null || tool.commandType().isBlank()) {
                throw new IllegalArgumentException("seedj.ai.tools 存在缺少 command-type 的条目");
            }
            if (tool.actionClass() == null) {
                throw new IllegalArgumentException(
                        "seedj.ai.tools 的 " + tool.commandType() + " 缺少 action-class");
            }
            catalog.put(
                    tool.commandType(), new AgentToolSpec(tool.commandType(), tool.actionClass()));
        }
        return Map.copyOf(catalog);
    }

    private static Map<String, Set<String>> contracts(List<AiProperties.Contract> contracts) {
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (AiProperties.Contract contract : contracts) {
            if (contract == null || contract.id() == null || contract.id().isBlank()) {
                continue;
            }
            parsed.put(contract.id(), new LinkedHashSet<>(contract.commands()));
        }
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        parsed.forEach((id, commands) -> copy.put(id, Set.copyOf(commands)));
        return Map.copyOf(copy);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("seedj.ai.llm.base-url 未配置");
        }
        String normalized =
                baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("seedj.ai.llm.base-url 必须以 http:// 或 https:// 开头");
        }
        return normalized;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY);
    }
}
