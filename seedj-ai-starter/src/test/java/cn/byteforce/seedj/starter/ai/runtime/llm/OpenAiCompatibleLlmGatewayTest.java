package cn.byteforce.seedj.starter.ai.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.runtime.Prompt;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI 兼容网关：请求形状、tool_call → Proposal、fail-closed 与重试。
 *
 * <p>断言里不出现真实 key 的明文语义——测试 key 是假的；生产断言靠 {@code LlmPropertiesTest} 的 toString 脱敏。
 */
class OpenAiCompatibleLlmGatewayTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String URL = "http://llm.test/v1/chat/completions";
    private static final String CONTRACT = "018f2e3a-0000-7000-8000-00000000c017";
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-0000-7000-8000-00000000e001");
    private static final String CREATE = "ticket.create";
    private static final String DELETE = "ticket.delete";

    private static final AiProperties AI =
            new AiProperties(
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(
                            new AiProperties.Tool(CREATE, ActionClass.REVERSIBLE),
                            new AiProperties.Tool(DELETE, ActionClass.COMPENSABLE)),
                    List.of(new AiProperties.Contract(CONTRACT, List.of(CREATE))));

    private MockRestServiceServer server;

    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("请求形状：模型、工具枚举、tool_choice、鉴权头与提示内容")
    void request_shape_is_openai_chat_completions() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("gpt-test"))
                .andExpect(jsonPath("$.tool_choice").value("required"))
                .andExpect(jsonPath("$.tools[0].function.name").value("execute_command"))
                .andExpect(
                        jsonPath("$.tools[0].function.parameters.properties.command_type.enum[0]")
                                .value(CREATE))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(
                        jsonPath(
                                "$.messages[1].content",
                                org.hamcrest.Matchers.containsString("打印机故障")))
                .andRespond(withSuccess(response("finish", "{\"reason\":\"完成\"}"), json()));

        gateway(properties(2)).next(prompt());
        server.verify();
    }

    @Test
    @DisplayName("execute_command：action_class 来自配置，payload/targets/rationale 原样映射")
    void parses_execute_command() {
        String arguments =
                "{\"command_type\":\"ticket.create\",\"payload\":{\"title\":\"打印机故障\"},"
                        + "\"rationale\":\"建工单\",\"targets\":[\"018f2e3a-0000-7000-8000-000000007731\"],"
                        + "\"expected_version\":7}";
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response("execute_command", arguments), json()));

        Proposal proposal = gateway(properties(2)).next(prompt());

        assertThat(proposal.kind()).isEqualTo(DecisionKind.EXECUTE_TOOL);
        assertThat(proposal.actionClass()).isEqualTo(ActionClass.REVERSIBLE);
        assertThat(proposal.command())
                .isEqualTo(
                        new CommandRef(
                                CREATE,
                                List.of(UUID.fromString("018f2e3a-0000-7000-8000-000000007731")),
                                7L));
        assertThat(proposal.payload()).containsEntry("title", "打印机故障");
        assertThat(proposal.rationale()).isEqualTo("建工单");
    }

    @Test
    @DisplayName("三个终结工具映射成 FINISH / ASK_USER / ESCALATE")
    void parses_terminal_tools() {
        for (String tool : List.of("finish", "ask_user", "escalate")) {
            server.expect(requestTo(URL))
                    .andRespond(withSuccess(response(tool, "{\"reason\":\"原因\"}"), json()));
        }
        OpenAiCompatibleLlmGateway gateway = gateway(properties(1));

        assertThat(gateway.next(prompt()).kind()).isEqualTo(DecisionKind.FINISH);
        assertThat(gateway.next(prompt()).kind()).isEqualTo(DecisionKind.ASK_USER);
        assertThat(gateway.next(prompt()).kind()).isEqualTo(DecisionKind.ESCALATE);
        server.verify();
    }

    @Test
    @DisplayName("契约过滤：不在契约里的命令不进枚举，也不接受")
    void contract_filters_command_enum() {
        server.expect(requestTo(URL))
                .andExpect(
                        jsonPath("$.tools[0].function.parameters.properties.command_type.enum")
                                .value(org.hamcrest.Matchers.contains(CREATE)))
                .andRespond(
                        withSuccess(
                                response(
                                        "execute_command",
                                        "{\"command_type\":\"ticket.delete\",\"payload\":{},"
                                                + "\"rationale\":\"删除\"}"),
                                json()));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    @DisplayName("一次多个 tool_call：拒绝，不挑第一个")
    void rejects_multiple_tool_calls() {
        Map<String, Object> call =
                Map.of("type", "function", "function", Map.of("name", "finish", "arguments", "{}"));
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "choices",
                                List.of(
                                        Map.of(
                                                "message",
                                                Map.of("tool_calls", List.of(call, call))))));
        server.expect(requestTo(URL)).andRespond(withSuccess(body, json()));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("一个工具调用");
    }

    @Test
    @DisplayName("没有 tool_calls：拒绝把纯文本当下一步")
    void rejects_text_only_response() {
        server.expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}", json()));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("没有返回工具调用");
    }

    @Test
    @DisplayName("未知工具名：拒绝")
    void rejects_unknown_tool() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("shell", "{}"), json()));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("未知工具");
    }

    @Test
    @DisplayName("参数不是 JSON：拒绝")
    void rejects_malformed_arguments() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("finish", "{"), json()));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("合法 JSON");
    }

    @Test
    @DisplayName("execute_command 缺 rationale：本地先拦")
    void rejects_missing_rationale() {
        assertRejected("{\"command_type\":\"ticket.create\",\"payload\":{}}", "rationale");
    }

    @Test
    @DisplayName("execute_command 的 rationale 超过 1KB：本地先拦")
    void rejects_oversized_rationale() {
        assertRejected(
                "{\"command_type\":\"ticket.create\",\"payload\":{},\"rationale\":\""
                        + "x".repeat(1025)
                        + "\"}",
                "rationale");
    }

    @Test
    @DisplayName("targets 不是 UUID：拒绝")
    void rejects_invalid_targets() {
        assertRejected(
                "{\"command_type\":\"ticket.create\",\"payload\":{},\"rationale\":\"建工单\","
                        + "\"targets\":[\"not-a-uuid\"]}",
                "UUID");
    }

    @Test
    @DisplayName("5xx 重试后成功")
    void retries_on_5xx() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(response("finish", "{}"), json()));

        Proposal proposal = gateway(properties(2)).next(prompt());

        assertThat(proposal.kind()).isEqualTo(DecisionKind.FINISH);
        server.verify();
    }

    @Test
    @DisplayName("4xx 不重试：配置错不该重试")
    void does_not_retry_on_4xx() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> gateway(properties(3)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("401");
        server.verify();
    }

    @Test
    @DisplayName("没有 api-key：不发 Authorization 头")
    void omits_authorization_without_key() {
        server.expect(requestTo(URL))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(response("finish", "{}"), json()));

        gateway(
                        new LlmProperties(
                                true,
                                "http://llm.test/v1/",
                                null,
                                "gpt-test",
                                null,
                                null,
                                Duration.ofSeconds(5),
                                1,
                                LlmProperties.ToolChoice.AUTO))
                .next(prompt());
        server.verify();
    }

    private void assertRejected(String arguments, String expectedMessage) {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response("execute_command", arguments), json()));

        assertThatThrownBy(() -> gateway(properties(1)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining(expectedMessage);
    }

    @Test
    @DisplayName("execute_command 省略 targets/expected_version：按空集合与不钉版本处理")
    void execute_command_allows_optional_fields() {
        server.expect(requestTo(URL))
                .andRespond(
                        withSuccess(
                                response(
                                        "execute_command",
                                        "{\"command_type\":\"ticket.create\",\"payload\":{},"
                                                + "\"rationale\":\"建工单\"}"),
                                json()));

        Proposal proposal = gateway(properties(1)).next(prompt());

        assertThat(proposal.command().targets()).isEmpty();
        assertThat(proposal.command().expectedVersion()).isNull();
        assertThat(proposal.payload()).isEmpty();
    }

    @Test
    @DisplayName("参数可以是对象（部分兼容端点直接给 object）")
    void accepts_arguments_as_object() {
        Map<String, Object> arguments =
                Map.of("command_type", CREATE, "payload", Map.of(), "rationale", "建工单");
        Map<String, Object> call =
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of("name", "execute_command", "arguments", arguments));
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "choices",
                                List.of(Map.of("message", Map.of("tool_calls", List.of(call))))));
        server.expect(requestTo(URL)).andRespond(withSuccess(body, json()));

        assertThat(gateway(properties(1)).next(prompt()).kind())
                .isEqualTo(DecisionKind.EXECUTE_TOOL);
    }

    @Test
    @DisplayName("空白 arguments 当成空对象：终结工具仍可收尾")
    void blank_arguments_are_empty_object() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("finish", ""), json()));

        assertThat(gateway(properties(1)).next(prompt()).kind()).isEqualTo(DecisionKind.FINISH);
    }

    @Test
    @DisplayName("function 不是对象：拒绝")
    void rejects_non_object_function() {
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "choices",
                                List.of(
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "tool_calls",
                                                        List.of(Map.of("function", "nope")))))));
        server.expect(requestTo(URL)).andRespond(withSuccess(body, json()));

        assertThatThrownBy(() -> gateway(properties(1)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("function");
    }

    @Test
    @DisplayName("响应缺 choices / 空响应体：拒绝")
    void rejects_malformed_envelope() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", json()));
        assertThatThrownBy(() -> gateway(properties(1)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("choices");

        setUp();
        server.expect(requestTo(URL)).andRespond(withSuccess("", json()));
        assertThatThrownBy(() -> gateway(properties(1)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("空响应体");
    }

    @Test
    @DisplayName("payload 不是对象：拒绝")
    void rejects_non_object_payload() {
        assertRejected(
                "{\"command_type\":\"ticket.create\",\"payload\":\"x\",\"rationale\":\"r\"}",
                "payload");
    }

    @Test
    @DisplayName("终结工具的 reason 不是字符串：忽略而不是崩溃")
    void terminal_reason_must_be_string() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response("finish", "{\"reason\":{\"x\":1}}"), json()));

        assertThat(gateway(properties(1)).next(prompt()).rationale()).isNull();
    }

    @Test
    @DisplayName("429 也会重试")
    void retries_on_429() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(response("finish", "{}"), json()));

        assertThat(gateway(properties(2)).next(prompt()).kind()).isEqualTo(DecisionKind.FINISH);
        server.verify();
    }

    @Test
    @DisplayName("传输错误重试耗尽：抛 LlmException")
    void throws_after_transport_failures() {
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("boom")));
        server.expect(requestTo(URL)).andRespond(withException(new java.io.IOException("boom")));

        assertThatThrownBy(() -> gateway(properties(2)).next(prompt()))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("传输层");
    }

    @Test
    @DisplayName("base-url 不是 http(s)：构造期拒绝")
    void rejects_non_http_base_url() {
        assertThatThrownBy(
                        () ->
                                new OpenAiCompatibleLlmGateway(
                                        builder,
                                        new LlmProperties(
                                                true,
                                                "ftp://llm.test",
                                                null,
                                                "gpt",
                                                null,
                                                null,
                                                Duration.ofSeconds(5),
                                                1,
                                                null),
                                        AI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("工具清单缺 command-type 或 action-class：构造期点名")
    void rejects_incomplete_tool_catalog() {
        AiProperties missingType =
                aiWithTools(List.of(new AiProperties.Tool(" ", ActionClass.READ_ONLY)));
        AiProperties missingClass = aiWithTools(List.of(new AiProperties.Tool(CREATE, null)));

        assertThatThrownBy(
                        () -> new OpenAiCompatibleLlmGateway(builder, properties(1), missingType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command-type");
        assertThatThrownBy(
                        () -> new OpenAiCompatibleLlmGateway(builder, properties(1), missingClass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action-class");
    }

    private OpenAiCompatibleLlmGateway gateway(LlmProperties properties) {
        return new OpenAiCompatibleLlmGateway(builder, properties, AI);
    }

    private static LlmProperties properties(int maxAttempts) {
        return new LlmProperties(
                true,
                "http://llm.test/v1",
                "sk-test",
                "gpt-test",
                null,
                null,
                Duration.ofSeconds(5),
                maxAttempts,
                LlmProperties.ToolChoice.REQUIRED);
    }

    private static AiProperties aiWithTools(List<AiProperties.Tool> tools) {
        return new AiProperties(true, null, null, null, null, null, tools, List.of());
    }

    private static Prompt prompt() {
        return new Prompt(CONTRACT, CONVERSATION, 1, "记录一次打印机故障", List.of());
    }

    private static String response(String tool, String arguments) {
        Map<String, Object> call =
                Map.of(
                        "type",
                        "function",
                        "function",
                        Map.of("name", tool, "arguments", arguments));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("tool_calls", List.of(call));
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", message))));
    }

    private static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }
}
