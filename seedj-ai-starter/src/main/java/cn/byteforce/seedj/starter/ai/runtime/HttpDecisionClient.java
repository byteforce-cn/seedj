package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.starter.ai.AiProperties;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 决策面的 HTTP 客户端：{@code POST {decisionBaseUrl}/v1/decisions} 求值， {@code POST
 * {decisionBaseUrl}/v1/decisions/{id}/outcome} 上报执行结局。
 *
 * <p>线格式与决策面的 {@code DecisionService.DecisionRequest} 同构（snake_case）： {@code task_contract_id} /
 * {@code conversation_id} / {@code step} / {@code kind} / {@code
 * command{type,payload,targets,expected_version}} / {@code rationale} / {@code evidence} / {@code
 * model} / {@code audience}。请求里<b>没有</b> {@code decision_id}、{@code idempotency_key} 与 {@code
 * args_hash}——它们由决策面生成或重算；token 只出现在响应里。
 *
 * <p><b>model 是部署事实，不是运行时输入</b>：来自 {@code seedj.ai.runtime.model.*}（见 {@link
 * AiRuntimeAutoConfiguration}），不由 agent 或请求方自报。换模型或换提示词版本必须改配置。
 *
 * <p><b>失败语义</b>：{@code deny} 是正常返回（{@link DecisionGrant#denyRule()} 有值）；HTTP 非 2xx 或响应缺必要 字段抛
 * {@link IllegalStateException}。异常消息只带状态码，不含响应体，也不含任何凭据。outcome 上报同样只带状态码。
 *
 * <p><b>T0 的调用方只会在 {@code EXECUTE_TOOL} 时调用本类</b>：{@link BoundedLoop} 不对只读/收尾提案求值（决策面 的 intent 要求
 * {@code command} 必填，而 {@code Proposal} 只对执行工具携带命令引用）；只读路径由宿主直接组合本类 时自行保证请求带得上 {@code command}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HttpDecisionClient implements DecisionClient {

    /** 决策面求值入口的路径。 */
    static final String DECISIONS_PATH = "/v1/decisions";

    /** 结局面报的路径后缀（接在 {@code /v1/decisions/{id}} 之后）。 */
    static final String OUTCOME_PATH_SUFFIX = "/outcome";

    private static final String VERDICT_ALLOW = "allow";

    private static final String VERDICT_DENY = "deny";

    private static final String VERDICT_PENDING = "pending_approval";

    /** 审批通过后取 token 的路径后缀。 */
    private static final String GRANT_PATH_SUFFIX = "/grant";

    private final RestClient client;

    private final String baseUrl;

    private final AiProperties.Runtime.Model model;

    /**
     * 用宿主的 HTTP 客户端构造器与决策面地址构造。
     *
     * @param builder Spring Boot 提供的 {@link RestClient.Builder}（超时由装配方在同一构造器上设置）
     * @param decisionBaseUrl 决策面 base URL，例如 {@code http://localhost:8080}
     * @param model 本次部署的模型引用（{@code seedj.ai.runtime.model.*}）
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException {@code decisionBaseUrl} 为空白
     */
    public HttpDecisionClient(
            RestClient.Builder builder, String decisionBaseUrl, AiProperties.Runtime.Model model) {
        this.client = Objects.requireNonNull(builder, "builder").build();
        this.baseUrl = baseUrl(decisionBaseUrl);
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * 求值一步。
     *
     * @param contract 任务契约
     * @param step 会话内步序
     * @param proposal 模型提议
     * @param audience token 受众；为空时请求里省略该键，由决策面按配置补
     * @return 决策判定
     * @throws NullPointerException {@code contract} 或 {@code proposal} 为 {@code null}
     * @throws IllegalArgumentException 契约标识不是 UUID
     * @throws IllegalStateException 决策面返回非 2xx、响应缺少 {@code decision_id}，或 {@code deny} 缺原因码
     */
    @Override
    public DecisionGrant ask(TaskContract contract, int step, Proposal proposal, String audience) {
        DecisionOutcome outcome = askOutcome(contract, step, proposal, audience);
        if (outcome instanceof DecisionOutcome.Pending pending) {
            throw new IllegalStateException("决策等待审批，需先 grant: " + pending.pending().decisionId());
        }
        if (outcome instanceof DecisionOutcome.Denied denied) {
            return denied.grant();
        }
        return ((DecisionOutcome.Granted) outcome).grant();
    }

    @Override
    public DecisionOutcome askOutcome(
            TaskContract contract, int step, Proposal proposal, String audience) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(proposal, "proposal");
        DecisionResponse response;
        try {
            response =
                    client.post()
                            .uri(baseUrl + DECISIONS_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(requestBody(contract, step, proposal, audience))
                            .retrieve()
                            .body(DecisionResponse.class);
        } catch (RestClientResponseException error) {
            throw new IllegalStateException("决策面返回 HTTP " + error.getStatusCode().value(), error);
        }
        if (response == null || response.decisionId() == null) {
            throw new IllegalStateException("决策面响应缺少 decision_id");
        }
        if (VERDICT_PENDING.equals(response.verdict())) {
            return new DecisionOutcome.Pending(
                    new DecisionPending(
                            response.decisionId(),
                            VERDICT_PENDING,
                            response.approvalDeadline(),
                            response.approvalInstanceId(),
                            response.approvalTaskId()));
        }
        DecisionGrant grant = grant(response);
        return grant.allowed()
                ? new DecisionOutcome.Granted(grant)
                : new DecisionOutcome.Denied(grant);
    }

    @Override
    public String grant(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        GrantResponse response;
        try {
            response =
                    client.post()
                            .uri(baseUrl + DECISIONS_PATH + "/" + decisionId + GRANT_PATH_SUFFIX)
                            .contentType(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .body(GrantResponse.class);
        } catch (RestClientResponseException error) {
            throw new IllegalStateException(
                    "决策面 grant 返回 HTTP " + error.getStatusCode().value(), error);
        }
        if (response == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalStateException("决策面 grant 响应缺少 token");
        }
        return response.token();
    }

    /**
     * 上报一次执行结局。
     *
     * <p>请求体只有 {@code command_id}（snake_case）：状态与链条目标识都只来自链上事实，运行时说了不算。
     *
     * @param decisionId 决策标识
     * @param commandId 命令标识
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalStateException 决策面返回非 2xx（消息只带状态码）
     */
    @Override
    public void report(UUID decisionId, UUID commandId) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(commandId, "commandId");
        try {
            client.post()
                    .uri(baseUrl + DECISIONS_PATH + "/" + decisionId + OUTCOME_PATH_SUFFIX)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("command_id", commandId.toString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException error) {
            throw new IllegalStateException(
                    "决策面 outcome 上报返回 HTTP " + error.getStatusCode().value(), error);
        }
    }

    private Map<String, Object> requestBody(
            TaskContract contract, int step, Proposal proposal, String audience) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_contract_id", contractId(contract.id()));
        body.put("conversation_id", contract.conversationId());
        body.put("step", step);
        body.put("kind", proposal.kind().wire());
        if (proposal.command() != null) {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("type", proposal.command().type());
            command.put("payload", proposal.payload());
            command.put("targets", proposal.command().targets());
            command.put("expected_version", proposal.command().expectedVersion());
            body.put("command", command);
        }
        if (proposal.rationale() != null && !proposal.rationale().isBlank()) {
            body.put("rationale", proposal.rationale());
        }
        if (!proposal.evidence().isEmpty()) {
            body.put("evidence", proposal.evidence());
        }
        body.put("model", modelBody());
        if (audience != null && !audience.isBlank()) {
            body.put("audience", audience);
        }
        return body;
    }

    private Map<String, Object> modelBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "name", model.name());
        putIfPresent(body, "version", model.version());
        putIfPresent(body, "prompt_ver", model.promptVer());
        putIfPresent(body, "params_digest", model.paramsDigest());
        return body;
    }

    private static DecisionGrant grant(DecisionResponse response) {
        if (response == null || response.decisionId() == null) {
            throw new IllegalStateException("决策面响应缺少 decision_id");
        }
        if (VERDICT_DENY.equals(response.verdict())) {
            if (response.denyRule() == null) {
                throw new IllegalStateException("决策面返回 deny 但缺少 deny_rule");
            }
            return new DecisionGrant(response.decisionId(), null, response.denyRule(), null);
        }
        if (!VERDICT_ALLOW.equals(response.verdict())) {
            throw new IllegalStateException("决策面返回未知 verdict: " + response.verdict());
        }
        return new DecisionGrant(
                response.decisionId(), response.token(), null, response.expiresAt());
    }

    private static UUID contractId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("task_contract_id 必须是 UUID: " + value, error);
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String baseUrl(String value) {
        Objects.requireNonNull(value, "decisionBaseUrl");
        String trimmed = value.replaceAll("/+$", "");
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("decisionBaseUrl must not be blank");
        }
        return trimmed;
    }

    /**
     * 决策面的响应体（线格式 snake_case）。
     *
     * @param decisionId 决策标识（也是幂等键）
     * @param intentRecordId 账本意图记录标识
     * @param verdict {@code allow} 或 {@code deny}
     * @param denyRule 拒绝原因码；放行时为空
     * @param token DecisionToken；只有执行工具的放行才有
     * @param expiresAt token 过期时间
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record DecisionResponse(
            UUID decisionId,
            UUID intentRecordId,
            String verdict,
            String denyRule,
            String token,
            Instant expiresAt,
            Instant approvalDeadline,
            String approvalInstanceId,
            String approvalTaskId) {}

    /**
     * grant 响应体。
     *
     * @param token JWS compact 串
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record GrantResponse(String token) {}
}
