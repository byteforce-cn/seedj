package cn.byteforce.seedj.it.ai;

import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.read.ReadGateway;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.guard.AgentGuardFilter;
import cn.byteforce.seedj.starter.ai.plane.ReconciliationJob;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * pilot 端到端用例的共享底座：真实 HTTP 客户端、数据库断言与「先签决策再提交命令」这两步。
 *
 * <p><b>全部走真实 HTTP 打本进程端口</b>（{@code @LocalServerPort}），不用 MockMvc：guard 是 servlet 过滤器链上的
 * 一环，runtime 也是真的 HTTP 客户端，只有真实请求才能证明「决策面签出的 token 能被执行侧验过」。
 *
 * <p>用例之间不共享状态：共享的只有「同一个 Spring 上下文 + 同一个 PG 容器」，因此每步断言都按本次的资源标识（唯一标题、 本次的 {@code
 * decision_id}）过滤，不依赖执行顺序。
 *
 * @author ByteForce
 * @since 1.0.0
 */
abstract class AbstractPilotIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Autowired protected JdbcTemplate jdbc;

    @Autowired protected DecisionLedger ledger;

    @Autowired protected TemporalStore objects;

    @Autowired protected ReadGateway reads;

    @Autowired protected ReconciliationJob reconciliation;

    @Autowired protected DecisionTokenIssuer issuer;

    @Autowired protected CallerProvider callers;

    /**
     * 新建一个 PostgreSQL 容器（每个 IT 各自一份，容器生命周期与测试类一致）。
     *
     * @return 容器
     */
    static PostgreSQLContainer<?> newPostgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("seedj")
                .withUsername("seedj")
                .withPassword("seedj");
    }

    /**
     * 把容器接成数据源。
     *
     * @param registry 动态属性注册表
     * @param container 本用例的容器
     */
    static void registerDatasource(
            DynamicPropertyRegistry registry, PostgreSQLContainer<?> container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    /** 本进程的 URL 前缀。 */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** runtime 用的模型引用：与 {@code seedj.ai.runtime.model.*} 同一个值。 */
    protected static AiProperties.Runtime.Model pilotModel() {
        return new AiProperties.Runtime.Model("pilot-model", "1", null, null);
    }

    /**
     * 一条已放行的决策：{@code decision_id} 同时是命令的幂等键。
     *
     * @param decisionId 决策标识
     * @param token DecisionToken
     */
    protected record Decision(UUID decisionId, String token) {}

    /**
     * 让决策面放行一次执行工具的提案，并取回 token。
     *
     * @param commandType 命令类型
     * @param payload 未脱敏载荷
     * @param targets 目标对象
     * @param expectedVersion 期望版本；{@code null} 表示不校验
     * @return 决策标识与 token
     */
    protected Decision allowExecute(
            String commandType,
            Map<String, Object> payload,
            List<UUID> targets,
            Long expectedVersion) {
        ResponseEntity<String> response =
                postJson(
                        "/v1/decisions",
                        json(decisionRequest(commandType, payload, targets, expectedVersion)));
        if (response.getStatusCode().value() != 200) {
            throw new IllegalStateException("决策面未放行: " + response.getBody());
        }
        String token = JsonPath.read(response.getBody(), "$.token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("allow 的执行工具步骤没有 token: " + response.getBody());
        }
        UUID decisionId = UUID.fromString(JsonPath.read(response.getBody(), "$.decision_id"));
        return new Decision(decisionId, token);
    }

    /**
     * 提交一条带决策 token 的命令。
     *
     * @param commandType 命令类型
     * @param payload 未脱敏载荷
     * @param targets 目标对象
     * @param expectedVersion 期望版本；{@code null} 表示不校验
     * @param idempotencyKey 幂等键（必须等于 {@code decision_id}）
     * @param token DecisionToken；{@code null} 表示故意不带
     * @return 原始响应
     */
    protected ResponseEntity<String> submit(
            String commandType,
            Map<String, Object> payload,
            List<UUID> targets,
            Long expectedVersion,
            UUID idempotencyKey,
            String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", commandType);
        body.put("payload", payload);
        body.put("idempotency_key", idempotencyKey.toString());
        body.put("targets", targets.stream().map(UUID::toString).toList());
        body.put("expected_version", expectedVersion);
        return postJson("/commands/" + commandType, json(body), token);
    }

    /**
     * 上报一次执行结局（runtime 在命令返回后做的事）。
     *
     * @param decisionId 决策标识
     * @param commandId 命令标识
     * @return 原始响应
     */
    protected ResponseEntity<String> reportOutcome(UUID decisionId, UUID commandId) {
        return postJson(
                "/v1/decisions/" + decisionId + "/outcome",
                json(Map.of("command_id", commandId.toString())));
    }

    /**
     * 按标题找本次创建的对象标识（标题在用例内唯一，因此不依赖执行顺序）。
     *
     * @param title 标题
     * @return 对象标识
     */
    protected UUID objectIdOf(String title) {
        return jdbc.queryForObject(
                "select locator from command_record"
                        + " where type = 'ticket.create' and payload->>'title' = ?",
                UUID.class,
                title);
    }

    /**
     * 命令记录行数。
     *
     * @return 行数
     */
    protected int commandRecordCount() {
        return count("command_record");
    }

    /**
     * 审计链条目行数。
     *
     * @return 行数
     */
    protected int auditEntryCount() {
        return count("audit_entry");
    }

    /**
     * 按决策标识读账本记录。
     *
     * @param decisionId 决策标识
     * @return 记录列表，按 {@code sequence} 升序
     */
    protected List<DecisionRecord> ledgerRecords(UUID decisionId) {
        return ledger.byDecision(PilotTestConfiguration.TENANT, decisionId);
    }

    /**
     * 发一个 JSON POST；不因状态码抛异常（401/403 也是被测结果）。
     *
     * @param path 路径
     * @param body 请求体 JSON
     * @return 原始响应
     */
    protected ResponseEntity<String> postJson(String path, String body) {
        return postJson(path, body, null);
    }

    /**
     * 发一个带决策 token 的 JSON POST；不因状态码抛异常。
     *
     * @param path 路径
     * @param body 请求体 JSON
     * @param token DecisionToken；{@code null} 表示不带
     * @return 原始响应
     */
    protected ResponseEntity<String> postJson(String path, String body, String token) {
        RestClient.RequestBodySpec request =
                http().post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = request.header(AgentGuardFilter.DECISION_HEADER, token);
        }
        return request.body(body)
                .retrieve()
                .onStatus(status -> true, (req, response) -> {})
                .toEntity(String.class);
    }

    /**
     * 从拒绝响应里读守卫的错误码。
     *
     * @param response 原始响应
     * @return {@code code} 字段
     */
    protected static String errorCode(ResponseEntity<String> response) {
        return JsonPath.read(response.getBody(), "$.code");
    }

    /**
     * 响应头的质询方案。
     *
     * @param response 原始响应
     * @return {@code WWW-Authenticate} 头
     */
    protected static String challenge(ResponseEntity<String> response) {
        return response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
    }

    private RestClient http() {
        return RestClient.builder().baseUrl(baseUrl()).build();
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    protected static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    protected static Map<String, Object> decisionRequest(
            String commandType,
            Map<String, Object> payload,
            List<UUID> targets,
            Long expectedVersion) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", commandType);
        command.put("payload", payload);
        command.put("targets", targets.stream().map(UUID::toString).toList());
        command.put("expected_version", expectedVersion);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_contract_id", PilotTestConfiguration.CONTRACT_ID);
        body.put("conversation_id", UUID.randomUUID().toString());
        body.put("step", 1);
        body.put("kind", "execute_tool");
        body.put("command", command);
        body.put("rationale", "pilot 用例：记录一次工单写动作");
        body.put("model", Map.of("name", "pilot-model", "version", "1"));
        return body;
    }
}
