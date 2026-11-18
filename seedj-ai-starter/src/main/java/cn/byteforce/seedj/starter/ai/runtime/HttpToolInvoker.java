package cn.byteforce.seedj.starter.ai.runtime;

import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 命令面的 HTTP 客户端：执行走 {@code POST {commandBaseUrl}/commands/{type}}，只读走
 * {@code GET {commandBaseUrl}/queries/{type}}。
 *
 * <p><b>token 只走带外</b>：DecisionToken 只放进请求头
 * {@value cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter#DECISION_HEADER}（与守卫读的是同一个常量），
 * <b>绝不进请求体</b>——请求体参与 {@code args_hash} 与审计哈希，token 里含 {@code args_hash}，塞进 body 会形成
 * 自引用（spec §5.1 关键 3）。{@code idempotency_key} 恒等于 {@code decisionId}。token 也不进日志与异常消息：本类
 * 不打日志，异常只在传输层抛出（不含请求体）。
 *
 * <p><b>结果语义</b>：2xx 为成功，其余（业务拒绝、幂等/版本冲突、准入失败）都是失败，响应体原样带回——业务拒绝是
 * 工具结果，不是传输异常。只读请求不带 token：只读路径不签发 DecisionToken（spec §5.1 只读路径）；{@code payload}
 * 作为 query 参数传给查询端点（T0 的循环不执行查询，{@code read} 供宿主直接驱动）。
 *
 * <p><b>路径里的命令类型来自模型输出</b>：拼进 URL 前先按 {@code [A-Za-z0-9_.-]+} 校验，含 {@code /}、{@code ..} 或
 * 空白的类型直接拒绝——否则模型能把请求导向另一个端点。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class HttpToolInvoker implements ToolInvoker {

    /** 命令提交路径前缀。 */
    static final String COMMANDS_PATH = "/commands/";

    /** 只读查询路径前缀。 */
    static final String QUERIES_PATH = "/queries/";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;

    private final String baseUrl;

    /**
     * 用宿主的 HTTP 客户端构造器与命令面地址构造。
     *
     * @param builder Spring Boot 提供的 {@link RestClient.Builder}（超时由装配方在同一构造器上设置）
     * @param commandBaseUrl 命令面 base URL，例如 {@code http://localhost:8080}
     * @throws NullPointerException 任一参数为 {@code null}
     * @throws IllegalArgumentException {@code commandBaseUrl} 为空白
     */
    public HttpToolInvoker(RestClient.Builder builder, String commandBaseUrl) {
        this.client = Objects.requireNonNull(builder, "builder").build();
        this.baseUrl = baseUrl(commandBaseUrl);
    }

    /**
     * 执行一次只读查询。
     *
     * @param call 查询类型与过滤条件
     * @return 查询结果
     * @throws NullPointerException {@code call} 为 {@code null}
     * @throws IllegalArgumentException 查询类型含非法字符
     */
    @Override
    public ToolResult read(Call call) {
        Objects.requireNonNull(call, "call");
        ResponseEntity<String> response =
                client.get()
                        .uri(queryUri(call))
                        .retrieve()
                        .onStatus(status -> true, (request, errorResponse) -> {})
                        .toEntity(String.class);
        return result(response);
    }

    /**
     * 执行一条命令。
     *
     * @param call 命令类型、载荷、目标与期望版本
     * @param token 决策面签发的 DecisionToken
     * @param decisionId 决策标识，同时是幂等键
     * @return 命令结果
     * @throws NullPointerException {@code call}、{@code token} 或 {@code decisionId} 为 {@code null}
     * @throws IllegalArgumentException {@code token} 为空白，或命令类型含非法字符
     */
    @Override
    public ToolResult execute(Call call, String token, UUID decisionId) {
        Objects.requireNonNull(call, "call");
        String type = commandType(call.commandType());
        String decisionToken = requiredToken(token);
        UUID idempotencyKey = Objects.requireNonNull(decisionId, "decisionId");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", call.payload());
        body.put("idempotency_key", idempotencyKey.toString());
        body.put("targets", call.targets());
        body.put("expected_version", call.expectedVersion());
        ResponseEntity<String> response =
                client.post()
                        .uri(baseUrl + COMMANDS_PATH + type)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AgentGuardFilter.DECISION_HEADER, decisionToken)
                        .body(body)
                        .retrieve()
                        .onStatus(status -> true, (request, errorResponse) -> {})
                        .toEntity(String.class);
        return result(response);
    }

    private static ToolResult result(ResponseEntity<String> response) {
        boolean ok = response.getStatusCode().is2xxSuccessful();
        String body = response.getBody() == null ? "" : response.getBody();
        return new ToolResult(ok, body, commandId(body));
    }

    private static UUID commandId(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode id = JSON.readTree(body).get("command_id");
            return id == null || id.isNull() ? null : UUID.fromString(id.asString());
        } catch (RuntimeException error) {
            return null;
        }
    }

    private URI queryUri(Call call) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromUriString(
                        baseUrl + QUERIES_PATH + commandType(call.commandType()));
        call.payload()
                .forEach(
                        (name, value) -> {
                            if (value != null) {
                                builder.queryParam(name, String.valueOf(value));
                            }
                        });
        return builder.build().encode().toUri();
    }

    /**
     * 校验命令类型：它来自模型输出，拼进 URL 前只允许保守的字符集。
     *
     * @throws IllegalArgumentException 含 {@code [A-Za-z0-9_.-]} 以外的字符
     */
    private static String commandType(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid command type");
        }
        return value;
    }

    private static String requiredToken(String value) {
        if (value == null) {
            throw new NullPointerException("token");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        return value;
    }

    private static String baseUrl(String value) {
        Objects.requireNonNull(value, "commandBaseUrl");
        String trimmed = value.replaceAll("/+$", "");
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("commandBaseUrl must not be blank");
        }
        return trimmed;
    }
}
