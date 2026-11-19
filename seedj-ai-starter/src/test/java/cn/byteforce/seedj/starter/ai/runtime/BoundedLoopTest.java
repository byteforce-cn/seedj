package cn.byteforce.seedj.starter.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 有界 agent 循环与两个 HTTP 端口（spec §5.1 两条路径、§7.2 token 只走 {@code x-agent-decision}）。
 *
 * <p>循环的每个用例都钉三件事：<b>只有写动作才问决策面</b>（{@code finish}/只读提案一次 {@code ask} 都不该有）、 <b>决策一次走一步</b>（多调一次
 * {@code ask} 或工具调用次数多一次都是错），以及<b>停止条件之后绝不再调工具</b>—— {@code stops_at_max_steps} 用工具调用次数证明上限真的是上限。
 *
 * <p>两个 HTTP 端口用 {@link MockRestServiceServer} 断言线格式：token 只出现在请求头，从不进 body；决策请求是 snake_case，且不接受
 * {@code decision_id}/{@code idempotency_key}。
 */
class BoundedLoopTest {

    private static final String CONTRACT_ID = "018f2e3a-0000-7000-8000-00000000c001";

    private static final UUID CONVERSATION_ID =
            UUID.fromString("018f2e3a-0000-7000-8000-00000000e001");

    private static final UUID DECISION_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000d0001");

    private static final UUID COMMAND_ID = UUID.fromString("018f2e3a-0000-7000-8000-0000000c0001");

    private static final UUID TARGET = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");

    private static final String TOKEN = "tkn-6f1c-decision-token-value";

    private static final String AUDIENCE = "billing-service";

    private static final String DECISION_BASE = "http://decisions.test";

    private static final String COMMAND_BASE = "http://commands.test";

    private static final int STEP_LIMIT = 10;

    @Test
    @DisplayName("循环到 maxSteps 就停：工具恰好调用 maxSteps 次")
    void stops_at_max_steps() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(tools.executed).hasSize(3);
        assertThat(tools.tokens).containsOnly(TOKEN);
        assertThat(decisions.calls).isEqualTo(3);
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.MAX_STEPS_REACHED);
        assertThat(result.steps()).isEqualTo(3);
    }

    @Test
    @DisplayName("部署上限比任务契约更紧时，以部署上限为准")
    void deployment_cap_is_tighter_than_contract() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result =
                new BoundedLoop(gateway, decisions, tools, 3, AUDIENCE).run(contract(10));

        assertThat(tools.executed).hasSize(3);
        assertThat(decisions.calls).isEqualTo(3);
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.MAX_STEPS_REACHED);
        assertThat(result.steps()).isEqualTo(3);
    }

    @Test
    @DisplayName("越权提案被拒：不调工具，detail 是 denyRule")
    void proposal_outside_capabilities_is_denied_without_tool_call() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions =
                new ScriptedDecisionClient(denyGrant("CONTRACT_CAPABILITY"));
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(tools.executed).isEmpty();
        assertThat(decisions.calls).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.DENIED);
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.detail()).isEqualTo("CONTRACT_CAPABILITY");
    }

    @Test
    @DisplayName("finish 直接收尾：不调工具")
    void finish_stops_without_tool_call() {
        ScriptedGateway gateway = new ScriptedGateway(finishProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(tools.executed).isEmpty();
        assertThat(decisions.calls).isZero();
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.FINISHED);
        assertThat(result.steps()).isEqualTo(1);
    }

    @Test
    @DisplayName("工具失败即停：不再有第二次决策")
    void tool_failure_stops_loop() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(false, "payment rejected", null));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(tools.executed).hasSize(1);
        assertThat(decisions.calls).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.TOOL_FAILED);
        assertThat(result.detail()).isEqualTo("payment rejected");
    }

    @Test
    @DisplayName("工具成功后 runtime 自己上报 outcome：每次执行一次，参数是 decision_id 与 command_id")
    void successful_tool_reports_outcome() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(2));

        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.MAX_STEPS_REACHED);
        assertThat(decisions.reported)
                .as("每次成功的执行都要有一条 outcome 上报，否则账本里永久「有 intent 无 outcome」")
                .containsExactly(DECISION_ID + "/" + COMMAND_ID, DECISION_ID + "/" + COMMAND_ID);
    }

    @Test
    @DisplayName("工具失败不上报 outcome：没有执行成功就没有结局")
    void failed_tool_reports_no_outcome() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(false, "payment rejected", null));

        loop(gateway, decisions, tools).run(contract(3));

        assertThat(decisions.reported).isEmpty();
    }

    @Test
    @DisplayName("上报失败只 WARN：命令已经执行成功，循环仍然 FINISHED")
    void report_failure_does_not_fail_the_loop() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal(), finishProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        decisions.reportFailure = new IllegalStateException("决策面 outcome 上报返回 HTTP 503");
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.FINISHED);
        assertThat(result.steps()).isEqualTo(2);
        assertThat(decisions.reported).hasSize(1);
        assertThat(tools.executed).hasSize(1);
    }

    @Test
    @DisplayName("EXECUTE_TOOL 缺 rationale：本地 fast-fail，不问决策面也不调工具")
    void execute_without_rationale_fails_locally() {
        Proposal noRationale =
                new Proposal(
                        DecisionKind.EXECUTE_TOOL,
                        ActionClass.REVERSIBLE,
                        new CommandRef("billing.refund", List.of(TARGET), 7L),
                        Map.of("amount", 100),
                        null,
                        List.of());
        ScriptedGateway gateway = new ScriptedGateway(noRationale);
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        assertThatThrownBy(() -> loop(gateway, decisions, tools).run(contract(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale");
        assertThat(decisions.calls).isZero();
        assertThat(tools.executed).isEmpty();
    }

    @Test
    @DisplayName("read 不是命令：只进观测，不调 execute")
    void read_proposal_is_not_executed_as_command() {
        ScriptedGateway gateway = new ScriptedGateway(readProposal(), finishProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(tools.executed).isEmpty();
        assertThat(tools.reads).isZero();
        assertThat(decisions.calls).isZero();
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.FINISHED);
        assertThat(result.steps()).isEqualTo(2);
    }

    @Test
    @DisplayName("每步都重算提示：第 2 步的 step 是 2，观测含第 1 步的内容")
    void prompt_is_recomputed_each_step() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "done", COMMAND_ID));

        loop(gateway, decisions, tools).run(contract(2));

        assertThat(gateway.prompts).hasSize(2);
        Prompt second = gateway.prompts.get(1);
        assertThat(second.step()).isEqualTo(2);
        assertThat(second.observations()).hasSize(1);
        assertThat(second.observations().get(0))
                .contains("step 1")
                .contains("billing.refund")
                .contains("ok");
    }

    @Test
    @DisplayName("execute 的 token 只在 x-agent-decision 头，不进 body")
    void execute_puts_token_in_header_not_body() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        server.expect(requestTo(COMMAND_BASE + "/commands/billing.refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-agent-decision", TOKEN))
                .andExpect(jsonPath("$.type").value("billing.refund"))
                .andExpect(jsonPath("$.payload.amount").value(100))
                .andExpect(jsonPath("$.targets[0]").value(TARGET.toString()))
                .andExpect(jsonPath("$.expected_version").value(7))
                .andExpect(jsonPath("$.idempotency_key").value(DECISION_ID.toString()))
                .andExpect(jsonPath("$.expectedVersion").doesNotExist())
                .andExpect(content().string(not(containsString(TOKEN))))
                .andRespond(
                        withStatus(HttpStatus.ACCEPTED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"command_id\":\""
                                                + COMMAND_ID
                                                + "\",\"status\":\"accepted\"}"));

        ToolResult result = invoker.execute(executeCall(), TOKEN, DECISION_ID);

        server.verify();
        assertThat(result.ok()).isTrue();
        assertThat(result.commandId()).isEqualTo(COMMAND_ID);
    }

    @Test
    @DisplayName("命令面 409 是失败结果，不是异常：循环以 TOOL_FAILED 收场")
    void tool_conflict_is_a_failed_result_not_an_exception() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        server.expect(requestTo(COMMAND_BASE + "/commands/billing.refund"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"code\":\"idempotency_conflict\",\"idempotency_key\":\""
                                                + DECISION_ID
                                                + "\"}"));

        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        LoopResult result = loop(gateway, decisions, invoker).run(contract(3));

        server.verify();
        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.TOOL_FAILED);
        assertThat(result.detail()).contains("idempotency_conflict");
    }

    @Test
    @DisplayName("命令类型含 / 时本地拒绝：请求根本发不出去")
    void command_type_with_slash_is_rejected_locally() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        assertThatThrownBy(
                        () ->
                                invoker.execute(
                                        new Call("billing/refund", Map.of(), List.of(), null),
                                        TOKEN,
                                        DECISION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid command type");
        server.verify();
    }

    @Test
    @DisplayName("命令类型 .. 被拒：路径归一化前就拦下")
    void command_type_with_dot_dot_is_rejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        assertThatThrownBy(
                        () ->
                                invoker.execute(
                                        new Call("..", Map.of(), List.of(), null),
                                        TOKEN,
                                        DECISION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid command type");
        server.verify();
    }

    @Test
    @DisplayName("命令类型尾部点被拒：ticket. 不是点分段标识符")
    void command_type_with_trailing_dot_is_rejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        assertThatThrownBy(
                        () ->
                                invoker.execute(
                                        new Call("ticket.", Map.of(), List.of(), null),
                                        TOKEN,
                                        DECISION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid command type");
        server.verify();
    }

    @Test
    @DisplayName("read 的命令类型含 / 同样本地拒绝：请求根本发不出去")
    void read_command_type_with_slash_is_rejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        assertThatThrownBy(() -> invoker.read(new Call("../x", Map.of(), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid command type");
        server.verify();
    }

    @Test
    @DisplayName("pending 结论让循环停下：不调工具、不报 outcome")
    void pending_outcome_stops_loop() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        PendingDecisionClient decisions = new PendingDecisionClient();
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "{}", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.PENDING_APPROVAL);
        assertThat(result.detail()).isEqualTo(DECISION_ID.toString());
        assertThat(tools.executed).isEmpty();
        assertThat(decisions.reports).isZero();
    }

    @Test
    @DisplayName("HTTP pending_approval 映射成 Pending；ask 拒绝把 pending 当放行")
    void http_pending_response_is_pending_outcome() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        String body = "{\"decision_id\":\"" + DECISION_ID + "\",\"verdict\":\"pending_approval\"}";
        server.expect(requestTo(DECISION_BASE + "/v1/decisions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(DECISION_BASE + "/v1/decisions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        DecisionOutcome outcome = client.askOutcome(contract(3), 2, executeProposal(), AUDIENCE);

        assertThat(outcome).isInstanceOf(DecisionOutcome.Pending.class);
        assertThat(((DecisionOutcome.Pending) outcome).pending().decisionId())
                .isEqualTo(DECISION_ID);

        assertThatThrownBy(() -> client.ask(contract(3), 2, executeProposal(), AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grant");
        server.verify();
    }

    @Test
    @DisplayName("grant POST /v1/decisions/{id}/grant 并返回 token")
    void grant_posts_to_grant_endpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions/" + DECISION_ID + "/grant"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess("{\"token\":\"" + TOKEN + "\"}", MediaType.APPLICATION_JSON));

        assertThat(client.grant(DECISION_ID)).isEqualTo(TOKEN);
        server.verify();
    }

    @Test
    @DisplayName("grant 非 2xx：抛 IllegalStateException，消息只带状态码")
    void grant_non_2xx_throws() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions/" + DECISION_ID + "/grant"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.grant(DECISION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("404");
        server.verify();
    }

    @Test
    @DisplayName("grant 响应缺 token：抛 IllegalStateException")
    void grant_missing_token_throws() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions/" + DECISION_ID + "/grant"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.grant(DECISION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
        server.verify();
    }

    @Test
    @DisplayName("决策请求是 snake_case，且不含 token")
    void decision_request_matches_wire_format() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.task_contract_id").value(CONTRACT_ID))
                .andExpect(jsonPath("$.conversation_id").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.step").value(2))
                .andExpect(jsonPath("$.kind").value("execute_tool"))
                .andExpect(jsonPath("$.command.type").value("billing.refund"))
                .andExpect(jsonPath("$.command.payload.amount").value(100))
                .andExpect(jsonPath("$.command.targets[0]").value(TARGET.toString()))
                .andExpect(jsonPath("$.command.expected_version").value(7))
                .andExpect(jsonPath("$.command.expectedVersion").doesNotExist())
                .andExpect(jsonPath("$.rationale").value("refund the order"))
                .andExpect(jsonPath("$.model.name").value("qwen2.5-72b"))
                .andExpect(jsonPath("$.model.version").value("2026-11-01"))
                .andExpect(jsonPath("$.model.prompt_ver").value("refund-v12"))
                .andExpect(jsonPath("$.model.params_digest").doesNotExist())
                .andExpect(jsonPath("$.model.promptVer").doesNotExist())
                .andExpect(jsonPath("$.audience").value(AUDIENCE))
                .andExpect(jsonPath("$.decision_id").doesNotExist())
                .andExpect(jsonPath("$.idempotency_key").doesNotExist())
                .andExpect(content().string(not(containsString(TOKEN))))
                .andRespond(withSuccess(allowResponse(), MediaType.APPLICATION_JSON));

        DecisionGrant grant = client.ask(contract(3), 2, executeProposal(), AUDIENCE);

        server.verify();
        assertThat(grant.allowed()).isTrue();
        assertThat(grant.decisionId()).isEqualTo(DECISION_ID);
        assertThat(grant.token()).isEqualTo(TOKEN);
        assertThat(grant.expiresAt()).isEqualTo(Instant.parse("2026-11-17T09:41:12Z"));
    }

    @Test
    @DisplayName("决策面非 2xx：抛 IllegalStateException，消息带状态码且不含 token")
    void decision_non_2xx_throws_without_token() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"code\":\"invalid_payload\"}"));

        assertThatThrownBy(() -> client.ask(contract(3), 2, executeProposal(), AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(TOKEN));
        server.verify();
    }

    @Test
    @DisplayName("outcome 上报：POST /v1/decisions/{id}/outcome，body 只有 command_id")
    void outcome_report_matches_wire_format() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions/" + DECISION_ID + "/outcome"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.command_id").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.audit_entry_id").doesNotExist())
                .andExpect(content().string(not(containsString(TOKEN))))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        client.report(DECISION_ID, COMMAND_ID);

        server.verify();
    }

    @Test
    @DisplayName("outcome 上报非 2xx：抛 IllegalStateException，消息只带状态码")
    void outcome_report_non_2xx_throws_with_status_code() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions/" + DECISION_ID + "/outcome"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"code\":\"unavailable\"}"));

        assertThatThrownBy(() -> client.report(DECISION_ID, COMMAND_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(TOKEN));
        server.verify();
    }

    @Test
    @DisplayName("audience 为空时决策请求不含 audience 键")
    void audience_omitted_when_null() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpDecisionClient client = new HttpDecisionClient(builder, DECISION_BASE, model());

        server.expect(requestTo(DECISION_BASE + "/v1/decisions"))
                .andExpect(content().string(not(containsString("audience"))))
                .andRespond(withSuccess(allowResponse(), MediaType.APPLICATION_JSON));

        DecisionGrant grant = client.ask(contract(3), 2, executeProposal(), null);

        server.verify();
        assertThat(grant.allowed()).isTrue();
    }

    @Test
    @DisplayName("观测里永远没有 token")
    void observations_never_contain_token() {
        ScriptedGateway gateway = new ScriptedGateway(executeProposal());
        ScriptedDecisionClient decisions = new ScriptedDecisionClient(allowGrant());
        RecordingToolInvoker tools =
                new RecordingToolInvoker(new ToolResult(true, "done", COMMAND_ID));

        LoopResult result = loop(gateway, decisions, tools).run(contract(3));

        assertThat(result.observations()).hasSize(3);
        assertThat(result.observations())
                .allSatisfy(line -> assertThat(line).doesNotContain(TOKEN));
        assertThat(result.observations().get(0))
                .contains("billing.refund")
                .contains(COMMAND_ID.toString());
    }

    @Test
    @DisplayName("read 走 GET /queries/{type}，不带决策 token")
    void read_uses_query_endpoint_without_token() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpToolInvoker invoker = new HttpToolInvoker(builder, COMMAND_BASE);

        server.expect(requestTo(COMMAND_BASE + "/queries/hr.employee?employee_id=e-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(
                        request ->
                                assertThat(request.getHeaders().getOrEmpty("x-agent-decision"))
                                        .isEmpty())
                .andRespond(
                        withSuccess(
                                "{\"command_id\":\"" + COMMAND_ID + "\",\"items\":[]}",
                                MediaType.APPLICATION_JSON));

        ToolResult result =
                invoker.read(
                        new Call("hr.employee", Map.of("employee_id", "e-1"), List.of(), null));

        server.verify();
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("没有 LLM 端口时不装配 BoundedLoop，补上就有一个")
    void runtime_auto_config_requires_llm_gateway() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(RuntimeHost.class)
                        .withConfiguration(AutoConfigurations.of(AiRuntimeAutoConfiguration.class))
                        .withPropertyValues(
                                "seedj.ai.runtime.enabled=true",
                                "seedj.ai.runtime.model.name=qwen2.5-72b",
                                "seedj.ai.runtime.model.version=2026-11-01");

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BoundedLoop.class);
                    assertThat(context).hasSingleBean(DecisionClient.class);
                    assertThat(context).hasSingleBean(ToolInvoker.class);
                });
        runner.withUserConfiguration(LlmHost.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(BoundedLoop.class);
                        });
    }

    @Test
    @DisplayName("runtime.enabled=true 时 model.name/version 必填：缺失即启动失败并点名配置键")
    void runtime_requires_model_name_and_version() {
        new ApplicationContextRunner()
                .withUserConfiguration(RuntimeHost.class)
                .withConfiguration(AutoConfigurations.of(AiRuntimeAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.ai.runtime.enabled=true",
                        "seedj.ai.runtime.model.version=2026-11-01")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(messages(context.getStartupFailure()))
                                    .contains("seedj.ai.runtime.model.name");
                        });
    }

    private static String messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" | ", messages);
    }

    private static BoundedLoop loop(
            LlmGateway gateway, DecisionClient decisions, ToolInvoker tools) {
        return new BoundedLoop(gateway, decisions, tools, STEP_LIMIT, AUDIENCE);
    }

    private static TaskContract contract(int maxSteps) {
        return new TaskContract(CONTRACT_ID, CONVERSATION_ID, "refund the order", maxSteps);
    }

    private static AiProperties.Runtime.Model model() {
        return new AiProperties.Runtime.Model("qwen2.5-72b", "2026-11-01", "refund-v12", null);
    }

    private static String allowResponse() {
        return "{\"decision_id\":\""
                + DECISION_ID
                + "\",\"intent_record_id\":\""
                + UUID.randomUUID()
                + "\",\"verdict\":\"allow\",\"deny_rule\":null,\"token\":\""
                + TOKEN
                + "\",\"expires_at\":\"2026-11-17T09:41:12Z\"}";
    }

    private static Proposal executeProposal() {
        return new Proposal(
                DecisionKind.EXECUTE_TOOL,
                ActionClass.REVERSIBLE,
                new CommandRef("billing.refund", List.of(TARGET), 7L),
                Map.of("amount", 100),
                "refund the order",
                List.of());
    }

    private static Proposal finishProposal() {
        return new Proposal(DecisionKind.FINISH, null, null, null, "done", List.of());
    }

    private static Proposal readProposal() {
        return new Proposal(DecisionKind.READ, null, null, null, "look up the order", List.of());
    }

    private static Call executeCall() {
        return new Call("billing.refund", Map.of("amount", 100), List.of(TARGET), 7L);
    }

    private static DecisionGrant allowGrant() {
        return new DecisionGrant(DECISION_ID, TOKEN, null, Instant.parse("2026-11-17T09:41:12Z"));
    }

    private static DecisionGrant denyGrant(String denyRule) {
        return new DecisionGrant(DECISION_ID, null, denyRule, null);
    }

    /** 按脚本出提案，脚本用尽后重复最后一条（「永远返回同一种提案」的用例靠它）。 */
    private static final class ScriptedGateway implements LlmGateway {

        private final List<Proposal> script;

        private final List<Prompt> prompts = new ArrayList<>();

        private int calls;

        ScriptedGateway(Proposal... script) {
            this.script = List.of(script);
        }

        @Override
        public Proposal next(Prompt prompt) {
            prompts.add(prompt);
            Proposal proposal = script.get(Math.min(calls, script.size() - 1));
            calls++;
            return proposal;
        }
    }

    private static final class ScriptedDecisionClient implements DecisionClient {

        private final List<DecisionGrant> grants;

        private final List<String> reported = new ArrayList<>();

        private int calls;

        private RuntimeException reportFailure;

        ScriptedDecisionClient(DecisionGrant... grants) {
            this.grants = List.of(grants);
        }

        @Override
        public DecisionGrant ask(
                TaskContract contract, int step, Proposal proposal, String audience) {
            DecisionGrant grant = grants.get(Math.min(calls, grants.size() - 1));
            calls++;
            return grant;
        }

        @Override
        public void report(UUID decisionId, UUID commandId) {
            reported.add(decisionId + "/" + commandId);
            if (reportFailure != null) {
                throw reportFailure;
            }
        }
    }

    private static final class PendingDecisionClient implements DecisionClient {

        private int reports;

        @Override
        public DecisionGrant ask(
                TaskContract contract, int step, Proposal proposal, String audience) {
            throw new AssertionError("ask must not be called for pending");
        }

        @Override
        public DecisionOutcome askOutcome(
                TaskContract contract, int step, Proposal proposal, String audience) {
            return new DecisionOutcome.Pending(
                    new DecisionPending(DECISION_ID, "pending_approval", null, null, null));
        }

        @Override
        public void report(UUID decisionId, UUID commandId) {
            reports++;
        }
    }

    private static final class RecordingToolInvoker implements ToolInvoker {

        private final ToolResult result;

        private final List<Call> executed = new ArrayList<>();

        private final List<String> tokens = new ArrayList<>();

        private int reads;

        RecordingToolInvoker(ToolResult result) {
            this.result = result;
        }

        @Override
        public ToolResult read(Call call) {
            reads++;
            return result;
        }

        @Override
        public ToolResult execute(Call call, String token, UUID decisionId) {
            executed.add(call);
            tokens.add(token);
            return result;
        }
    }

    /** 装配期宿主：只有启用标记与 HTTP 客户端构造器，LLM 端口由用例决定给不给。 */
    @Configuration(proxyBeanMethods = false)
    static class RuntimeHost {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    /** 宿主提供 LLM 端口：BoundedLoop 才装配。 */
    @Configuration(proxyBeanMethods = false)
    static class LlmHost {

        @Bean
        LlmGateway llmGateway() {
            return prompt -> finishProposal();
        }
    }
}
