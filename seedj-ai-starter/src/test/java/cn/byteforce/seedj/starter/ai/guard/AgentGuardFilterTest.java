package cn.byteforce.seedj.starter.ai.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.autoconfigure.AiGuardAutoConfiguration;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenVerifier;
import cn.byteforce.seedj.starter.cqrs.web.CommandRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 执行侧 guard 过滤器：没有合法 DecisionToken 就不许提交写命令。
 *
 * <p>用例覆盖三件事：拦的是哪些请求（只拦单段 {@code POST /commands/*}）、拒绝时给出哪个状态码与码字（401 缺凭据 / 403 token 校验结论 / 400
 * 坏载荷）、以及 {@code args_hash} 与版本是按**原始请求体**算的——换成脱敏后的载荷或忽略 {@code If-Match} 都会让 token 钉死的版本守卫失效。
 *
 * <p>过滤器测试不启 Spring MVC 上下文：{@code MockMvcBuilders.standaloneSetup} 装配真实 {@link AgentGuardFilter}
 * 与真实 {@link DecisionTokenVerifier}，只有「启动期校验」与「装配开关」两个用例用 {@link WebApplicationContextRunner}
 * 验证自动配置。
 */
class AgentGuardFilterTest {

    private static final String COMMAND_TYPE = "billing.refund";
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID DECISION_ID = UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final UUID OTHER_DECISION_ID =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000a002");
    private static final UUID LEDGER_INTENT_ID =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000d001");
    private static final UUID TARGET_1 = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final UUID TARGET_2 = UUID.fromString("018f2e3a-0000-7000-8000-000000007732");
    private static final List<UUID> TARGETS = List.of(TARGET_1);
    private static final Long EXPECTED_VERSION = 7L;
    private static final Map<String, Object> PAYLOAD =
            Map.of("amount", "128.00", "currency", "CNY");

    private static final String KID = "agent-decision-test";
    private static final String AUDIENCE = "seedj-server-ai";
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final Instant NOW = Instant.parse("2026-11-17T09:41:00Z");
    private static final Instant IAT = NOW.minusSeconds(30);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final DecisionTokenIssuer ISSUER =
            new DecisionTokenIssuer(new AiProperties.Decision(seed(), KID, TTL, AUDIENCE));
    private static final DecisionTokenVerifier VERIFIER =
            new DecisionTokenVerifier(
                    Map.of(KID, ISSUER.jwks().publicKey()),
                    new ConfiguredToolRegistry(
                            List.of(new AgentToolSpec(COMMAND_TYPE, ActionClass.REVERSIBLE))),
                    AUDIENCE);

    private final AtomicBoolean commandSubmitted = new AtomicBoolean();
    private final AtomicBoolean commandQueried = new AtomicBoolean();
    private final AtomicReference<Map<String, Object>> receivedPayload = new AtomicReference<>();
    private final AtomicReference<String> receivedReaderBody = new AtomicReference<>();
    private final AtomicInteger receivedContentLength = new AtomicInteger();
    private final AtomicReference<Caller> currentCaller = new AtomicReference<>(caller());
    private final CallerProvider callers = () -> Optional.ofNullable(currentCaller.get());

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commandSubmitted.set(false);
        commandQueried.set(false);
        receivedPayload.set(null);
        receivedReaderBody.set(null);
        receivedContentLength.set(-1);
        currentCaller.set(caller());
        mvc = mvcWith(CallerIdCodec.uuid());
    }

    /** 用给定 codec 建一个独立的 MVC 实例：actor 的解析策略必须与 {@code CommandController} 一致。 */
    private MockMvc mvcWith(CallerIdCodec codec) {
        AgentGuardFilter filter =
                new AgentGuardFilter(VERIFIER, callers, codec, AUDIENCE, (Clock) () -> NOW, MAPPER);
        return MockMvcBuilders.standaloneSetup(new ProbeController()).addFilter(filter).build();
    }

    @Test
    @DisplayName("缺 x-agent-decision 时 401 且不进入控制器")
    void missing_header_is_rejected_before_controller() throws Exception {
        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(requestBody())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "AgentDecision"))
                .andExpect(jsonPath("$.code").value("token_missing"));

        assertThat(commandSubmitted).isFalse();
    }

    @Test
    @DisplayName("GET /commands/{id} 不在拦截面内")
    void read_commands_are_not_gated() throws Exception {
        mvc.perform(get("/commands/" + TARGET_1)).andExpect(status().isOk());

        assertThat(commandQueried).isTrue();
    }

    @Test
    @DisplayName("token 按 payload A 签发、请求发 payload B 时 403 args_hash_mismatch")
    void tampered_payload_is_rejected() throws Exception {
        String token = token(COMMAND, PAYLOAD);
        Map<String, Object> body = requestBody();
        body.put("payload", Map.of("amount", "999.00", "currency", "CNY"));

        assertRejected(token, body, null, "args_hash_mismatch");
    }

    @Test
    @DisplayName("换目标对象时 403 targets_mismatch")
    void swapped_target_is_rejected() throws Exception {
        String token = token(COMMAND, PAYLOAD);
        Map<String, Object> body = requestBody();
        body.put("targets", List.of(TARGET_2.toString()));

        assertRejected(token, body, null, "targets_mismatch");
    }

    @Test
    @DisplayName("args_hash 按请求体原文算：writeOnly 字段也参与")
    void args_hash_uses_raw_body_before_redaction() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("amount", "128.00");
        // secret 是命令记录脱敏会剔除的键（见 CommandRuntime 的 storedPayload）；token 按原文签，
        // 若 guard 用脱敏后的载荷算 args_hash，这条请求会被 403。
        raw.put("secret", "s3cr3t");
        Map<String, Object> body = requestBody();
        body.put("payload", raw);

        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(COMMAND, raw))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isAccepted());

        assertThat(commandSubmitted).isTrue();
        // 控制器拿到的是原文：脱敏只影响落库，不能影响执行面。
        assertThat(receivedPayload.get()).isEqualTo(raw);
    }

    @Test
    @DisplayName("控制器读到的是原文：请求体对 reader 与 content length 都是可重放的")
    void request_body_is_replayable_for_the_controller() throws Exception {
        String body = json(requestBody());

        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(COMMAND, PAYLOAD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isAccepted());

        assertThat(receivedReaderBody.get()).isEqualTo(body);
        assertThat(receivedContentLength.get())
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("幂等键与 token 的 decision_id 不同时 403 idempotency_key_mismatch")
    void mismatched_idempotency_key_is_rejected() throws Exception {
        Map<String, Object> body = requestBody();
        body.put("idempotency_key", OTHER_DECISION_ID.toString());

        assertRejected(token(COMMAND, PAYLOAD), body, null, "idempotency_key_mismatch");
    }

    @Test
    @DisplayName("没有已认证调用方时 401")
    void missing_caller_is_unauthorized() throws Exception {
        currentCaller.set(null);

        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(COMMAND, PAYLOAD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(requestBody())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "AgentDecision"))
                .andExpect(jsonPath("$.code").value("unauthenticated"));

        assertThat(commandSubmitted).isFalse();
    }

    @Test
    @DisplayName("actor 由 CallerIdCodec 解析，而不是硬编码 UUID 解析")
    void actor_is_resolved_by_caller_id_codec() throws Exception {
        // 名称型 IdP 的标识不是 UUID：guard 与 CommandController 必须共用同一个 codec 策略。
        currentCaller.set(caller("agent-runtime-1", TENANT.toString()));

        mvcWith(ignored -> ACTOR)
                .perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(COMMAND, PAYLOAD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(requestBody())))
                .andExpect(status().isAccepted());

        assertThat(commandSubmitted).isTrue();

        // 同一个标识在 uuid() 策略下解析不出来：401，既不 500 也不放行。
        commandSubmitted.set(false);
        mvcWith(CallerIdCodec.uuid())
                .perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(COMMAND, PAYLOAD))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(requestBody())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));

        assertThat(commandSubmitted).isFalse();
    }

    @Test
    @DisplayName("非 UUID 租户按不透明字符串比较：t_07 通过")
    void non_uuid_tenant_is_accepted() throws Exception {
        currentCaller.set(caller(ACTOR.toString(), "t_07"));

        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(
                                        AgentGuardFilter.DECISION_HEADER,
                                        token(COMMAND, PAYLOAD, "t_07"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(requestBody())))
                .andExpect(status().isAccepted());

        assertThat(commandSubmitted).isTrue();
    }

    @Test
    @DisplayName("租户不一致时 403 identity_mismatch")
    void tenant_mismatch_is_rejected() throws Exception {
        currentCaller.set(caller(ACTOR.toString(), "t_08"));

        assertRejected(token(COMMAND, PAYLOAD, "t_07"), requestBody(), null, "identity_mismatch");
    }

    @Test
    @DisplayName("targets 含 null 是 400 invalid_payload，不是 500")
    void null_target_element_is_bad_request() throws Exception {
        Map<String, Object> body = requestBody();
        List<Object> targets = new ArrayList<>();
        targets.add(null);
        body.put("targets", targets);

        assertRejected(token(COMMAND, PAYLOAD), body, null, "invalid_payload");
    }

    @Test
    @DisplayName("If-Match 参与生效版本与 args_hash：body 不给版本时按 header 算")
    void if_match_version_feeds_args_hash() throws Exception {
        // 与 CommandController 同一套优先级：两处都给且不等才是 400（见 invalidPayloads 的用例），
        // 只给 If-Match 时必须按 header 算——忽略它就会把钉死版本的 token 判成 version_mismatch。
        CommandRef pinned = new CommandRef(COMMAND_TYPE, TARGETS, 8L);
        Map<String, Object> body = requestBody();
        body.remove("expected_version");

        mvc.perform(
                        post("/commands/" + COMMAND_TYPE)
                                .header(AgentGuardFilter.DECISION_HEADER, token(pinned, PAYLOAD))
                                .header(HttpHeaders.IF_MATCH, "\"8\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(body)))
                .andExpect(status().isAccepted());

        assertThat(commandSubmitted).isTrue();
    }

    @Test
    @DisplayName("token 钉死版本但请求不提版本时 403 version_mismatch")
    void token_pins_version_but_request_omits_it_is_rejected() throws Exception {
        CommandRef pinned = new CommandRef(COMMAND_TYPE, TARGETS, 8L);
        Map<String, Object> body = requestBody();
        body.remove("expected_version");

        assertRejected(token(pinned, PAYLOAD), body, null, "version_mismatch");
    }

    @Test
    @DisplayName("超过 1 MiB 的请求体 400 且不进入控制器")
    void oversized_body_is_rejected() throws Exception {
        Map<String, Object> body = requestBody();
        body.put("payload", Map.of("amount", "128.00", "note", "x".repeat(1024 * 1024 + 1)));

        assertRejected(token(COMMAND, PAYLOAD), body, null, "invalid_payload");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    @DisplayName("坏载荷与非法版本声明 400 invalid_payload，不进入控制器")
    void invalid_payload_is_rejected_before_controller(
            String name, Consumer<Map<String, Object>> tweak, String ifMatch) throws Exception {
        Map<String, Object> body = requestBody();
        tweak.accept(body);

        assertRejected(token(COMMAND, PAYLOAD), body, ifMatch, "invalid_payload");
    }

    @Test
    @DisplayName("tools 里出现命令目录没有的类型时启动失败并点名")
    void unregistered_tool_fails_fast_at_startup() {
        guardRunner()
                .withPropertyValues(
                        "seedj.ai.tools[0].command-type=ticket.missing",
                        "seedj.ai.tools[0].action-class=compensable")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("ticket.missing");
                        });
    }

    @Test
    @DisplayName("guard.enabled=true 但 audience 空白：启动失败并点名 seedj.ai.decision.audience")
    void guard_requires_audience_at_startup() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiGuardAutoConfiguration.class))
                .withUserConfiguration(GuardHost.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(messages(context.getStartupFailure()))
                                    .as("受众只能来自配置：留空就只能靠请求头猜，等于允许跨服务重放")
                                    .contains("seedj.ai.decision.audience");
                        });
    }

    @Test
    @DisplayName("guard.enabled=false 时不注册过滤器")
    void guard_disabled_registers_no_filter() {
        guardRunner()
                .withPropertyValues("seedj.ai.guard.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(AgentGuardFilter.class);
                        });

        // 反证：同一套配置在默认开关下确实注册过滤器，否则上面的断言是空真。
        guardRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(AgentGuardFilter.class);
                            assertThat(context).hasBean("agentGuardFilterRegistration");
                        });
    }

    @Test
    @DisplayName("guard 注册在安全链之后：顺序错了就是生产 100% 401")
    void guard_runs_after_security_chain() {
        guardRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            FilterRegistrationBean<?> registration =
                                    context.getBean(
                                            "agentGuardFilterRegistration",
                                            FilterRegistrationBean.class);

                            // 安全链（springSecurityFilterChain）先填 Caller，guard 必须排在它后面；
                            // 任何 filter 都早于 DispatcherServlet，所以上界取 0 即可保证在命令入口之前。
                            assertThat(registration.getOrder())
                                    .isGreaterThan(SecurityFilterProperties.DEFAULT_FILTER_ORDER)
                                    .isLessThan(0);
                            assertThat(registration.getUrlPatterns())
                                    .containsExactly("/commands/*");
                        });
    }

    private static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                arguments(
                        "缺少 type",
                        (Consumer<Map<String, Object>>) body -> body.remove("type"),
                        null),
                arguments(
                        "缺少 payload",
                        (Consumer<Map<String, Object>>) body -> body.remove("payload"),
                        null),
                arguments(
                        "缺少 idempotency_key",
                        (Consumer<Map<String, Object>>) body -> body.remove("idempotency_key"),
                        null),
                arguments("If-Match 不是版本号", (Consumer<Map<String, Object>>) body -> {}, "v7"),
                arguments(
                        "If-Match 与 expected_version 不一致",
                        (Consumer<Map<String, Object>>) body -> {},
                        "9"));
    }

    private void assertRejected(
            String token, Map<String, Object> body, String ifMatch, String expectedCode)
            throws Exception {
        var request =
                post("/commands/" + COMMAND_TYPE)
                        .header(AgentGuardFilter.DECISION_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body));
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        var expectedStatus =
                "invalid_payload".equals(expectedCode)
                        ? status().isBadRequest()
                        : status().isForbidden();

        mvc.perform(request)
                .andExpect(expectedStatus)
                .andExpect(jsonPath("$.code").value(expectedCode));

        assertThat(commandSubmitted).isFalse();
    }

    private static final CommandRef COMMAND =
            new CommandRef(COMMAND_TYPE, TARGETS, EXPECTED_VERSION);

    private static String token(CommandRef command, Map<String, Object> payload) {
        return token(command, payload, TENANT.toString());
    }

    private static String token(CommandRef command, Map<String, Object> payload, String tenant) {
        String argsHash =
                ArgsHash.of(
                        new CommandFingerprint(
                                command.type(),
                                payload,
                                command.targets(),
                                command.expectedVersion()));
        DecisionTokenClaims claims =
                new DecisionTokenClaims(
                        1,
                        DECISION_ID,
                        LEDGER_INTENT_ID,
                        command,
                        argsHash,
                        ACTOR,
                        ON_BEHALF_OF,
                        tenant,
                        ActionClass.REVERSIBLE,
                        PolicyRef.none(),
                        null,
                        IAT,
                        IAT.plus(TTL),
                        AUDIENCE);
        return ISSUER.sign(claims);
    }

    private static Map<String, Object> requestBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", COMMAND_TYPE);
        body.put("payload", PAYLOAD);
        body.put("idempotency_key", DECISION_ID.toString());
        body.put("targets", TARGETS.stream().map(UUID::toString).toList());
        body.put("expected_version", EXPECTED_VERSION);
        return body;
    }

    /** 把启动失败的整条 cause 链拍成一行：断言配置键名用。 */
    private static String messages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" | ", messages);
    }

    private static String json(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    private static Caller caller() {
        return caller(ACTOR.toString(), TENANT.toString());
    }

    private static Caller caller(String id, String tenant) {
        return new Caller(
                id, "agent-runtime", tenant, Set.of(), Set.of(), Set.of(), Set.of(), null, null);
    }

    private static String seed() {
        byte[] seed = new byte[32];
        for (int index = 0; index < seed.length; index++) {
            seed[index] = (byte) (index + 1);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(seed);
    }

    private static WebApplicationContextRunner guardRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiGuardAutoConfiguration.class))
                .withUserConfiguration(GuardHost.class)
                .withPropertyValues("seedj.ai.decision.audience=" + AUDIENCE);
    }

    /** 一个最小的 guard 宿主：启用标记、调用方、时钟与（空）命令目录。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class GuardHost {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        CallerProvider callerProvider() {
            return () -> Optional.of(caller());
        }

        @Bean
        Clock clock() {
            return () -> NOW;
        }

        @Bean
        CommandCatalog commandCatalog() {
            Clock clock = () -> NOW;
            CommandRuntime runtime =
                    new CommandRuntime(
                            new CommandRuntime.Options(
                                    TransactionRunner.direct(),
                                    LockProvider.disabled(),
                                    Duration.ofSeconds(30),
                                    clock,
                                    new Uuid7IdGenerator(clock),
                                    new InMemoryCommandStore(),
                                    new InMemoryTemporalStore(clock),
                                    OutboxWriter.discard(),
                                    TemporalCommandAudit.noop()));
            return new CommandCatalog(
                    List.of(),
                    CommandPolicy.permissions(),
                    runtime,
                    (objectType, caller) -> ScopePredicate.all());
        }
    }

    /** 探针控制器：记录是否真的被调用。 */
    @RestController
    final class ProbeController {

        @PostMapping("/commands/{commandType}")
        ResponseEntity<Map<String, Object>> submit(
                @PathVariable String commandType,
                @RequestBody CommandRequest request,
                HttpServletRequest httpRequest)
                throws IOException {
            commandSubmitted.set(true);
            receivedPayload.set(request.payload());
            receivedContentLength.set(httpRequest.getContentLength());
            receivedReaderBody.set(readAll(httpRequest.getReader()));
            return ResponseEntity.status(202)
                    .body(Map.of("status", "accepted", "type", request.type()));
        }

        @GetMapping("/commands/{id}")
        ResponseEntity<Map<String, Object>> status(@PathVariable UUID id) {
            commandQueried.set(true);
            return ResponseEntity.ok(Map.of("status", "done", "command_id", id.toString()));
        }
    }

    private static String readAll(BufferedReader reader) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] chunk = new char[1024];
        int read = reader.read(chunk);
        while (read != -1) {
            text.append(chunk, 0, read);
            read = reader.read(chunk);
        }
        return text.toString();
    }
}
