package cn.byteforce.seedj.starter.ai.plane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.AgentToolSpec;
import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ArgsHash;
import cn.byteforce.seedj.ai.CommandFingerprint;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.Evidence;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.PolicyRef;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.ai.spi.ToolRegistry;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.local.ai.InMemoryDecisionLedger;
import cn.byteforce.seedj.starter.ai.AiEnabledMarker;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.autoconfigure.DecisionPlaneAutoConfiguration;
import cn.byteforce.seedj.starter.ai.guard.ConfiguredToolRegistry;
import cn.byteforce.seedj.starter.ai.policy.PolicyEvaluator;
import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * 决策面的顺序与边界（spec §5.1 只读路径、§6.2 账本记录、§7.1 claim）。
 *
 * <p>核心断言是<b>顺序</b>：intent 先落账、token 后签发，中间任何一步失败都不能让「无意图的执行」发生。因此用例用
 * 一个记账的账本装饰器与一个可抛异常的签发口记录事件序列，而不是只看最终响应。
 *
 * <p>纯 JUnit：不启动 Spring。只有装配期用例（缺 {@code plane.tenant} 启动失败、JWKS 端点开关、多个 {@code IdGenerator<UUID>}
 * 时回退随机 UUID）用 {@link WebApplicationContextRunner}，因为它们是 {@code @ConditionalOnProperty}、构造期校验与 bean
 * 解析的行为。
 */
class DecisionServiceTest {

    private static final UUID TENANT = UUID.fromString("018f2e3a-0000-7000-8000-0000000b0007");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e01");
    private static final UUID OTHER_ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-0000000a9e02");
    private static final UUID ON_BEHALF_OF =
            UUID.fromString("018f2e3a-0000-7000-8000-000000000118");
    private static final UUID OTHER_USER = UUID.fromString("018f2e3a-0000-7000-8000-000000000119");
    private static final UUID CONTRACT = UUID.fromString("018f2e3a-1234-7000-8000-00000000c001");
    private static final UUID UNKNOWN_CONTRACT =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000c002");
    private static final UUID CONVERSATION =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000e001");
    private static final UUID TARGET_A = UUID.fromString("018f2e3a-0000-7000-8000-000000007731");
    private static final UUID TARGET_B = UUID.fromString("018f2e3a-0000-7000-8000-000000007732");

    /** 契约允许、注册表登记为可逆的命令。 */
    private static final String CREATE = "ticket.create";

    /** 注册表登记为可补偿、但不在契约里：deny 的典型来源。 */
    private static final String DELETE = "ticket.delete";

    /** 契约允许、注册表登记为只读的命令。 */
    private static final String GET = "ticket.get";

    /** 注册表里完全没有的命令：坏请求而不是 deny。 */
    private static final String UNREGISTERED = "ticket.purge";

    private static final Map<String, Object> PAYLOAD =
            Map.of("amount", "128.00", "currency", "CNY");
    private static final Long EXPECTED_VERSION = 7L;
    private static final Instant NOW = Instant.parse("2026-11-17T09:41:00Z");
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final String AUDIENCE = "ticket-service";
    private static final String RATIONALE = "为工单开票";
    private static final UUID FIXED_DECISION_ID =
            UUID.fromString("018f2e3a-1234-7000-8000-00000000a001");
    private static final String SEED = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private static final String KID = "agent-decision-test";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<String> events = new ArrayList<>();

    private final List<DecisionIntent> intents = new ArrayList<>();

    private final List<DecisionTokenClaims> signed = new ArrayList<>();

    private final InMemoryDecisionLedger ledger = new InMemoryDecisionLedger();

    private final ToolRegistry tools = registry();

    private final CapabilityRegistry capabilities = capabilities();

    @Test
    @DisplayName("签发失败：intent 已落账，再写 outcome(not_issued)，异常仍向上抛")
    void intent_is_committed_before_token_is_returned() {
        DecisionService service =
                service(
                        claims -> {
                            events.add("sign");
                            throw new IllegalStateException("signing failed");
                        });

        assertThatThrownBy(() -> service.decide(executeTool(CREATE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing failed");

        assertThat(events).containsExactly("intent", "sign", "outcome");
        assertThat(intents).hasSize(1);
        List<DecisionRecord> records = ledger.byDecision(TENANT, intents.get(0).decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        // 来源是决策面自己：只有 runtime 上报与对账才写 runtime_reported / reconciled。
        assertThat(records.get(1).body()).containsEntry("source", "plane_decided");
        assertThat(effectOf(records.get(1)))
                .containsEntry("status", "not_issued")
                .containsEntry("error_code", DecisionService.SIGNING_FAILED);
    }

    @Test
    @DisplayName("能力集外的命令：deny + intent/outcome，不签 token")
    void deny_writes_intent_and_outcome() {
        DecisionService.DecisionResult result = service().decide(executeTool(DELETE));

        assertThat(result.verdict()).isEqualTo("deny");
        assertThat(result.denyRule()).isEqualTo("CONTRACT_CAPABILITY");
        assertThat(result.token()).isNull();
        assertThat(result.expiresAt()).isNull();
        assertThat(events).containsExactly("intent", "outcome");

        List<DecisionRecord> records = ledger.byDecision(TENANT, result.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        assertThat(records.get(1).body()).containsEntry("source", "plane_decided");
        assertThat(effectOf(records.get(1)))
                .containsEntry("status", "rejected")
                .containsEntry("error_code", "CONTRACT_CAPABILITY");
    }

    @Test
    @DisplayName("deny 也要求命令已注册：缺项是坏请求，不落账")
    void unregistered_command_is_bad_request() {
        DecisionService service = service();

        assertThatThrownBy(() -> service.decide(executeTool(UNREGISTERED)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains(UNREGISTERED);
                        });
        assertThat(events).isEmpty();
        assertThat(intents).isEmpty();
    }

    @Test
    @DisplayName("execute_tool 既无请求 audience 也无配置 audience：400，且不落账")
    void execute_tool_without_audience_is_bad_request() {
        DecisionService service = service(recordingSigner(), UUID::randomUUID, null);

        assertThatThrownBy(
                        () ->
                                service.decide(
                                        executeTool(
                                                CONTRACT,
                                                CREATE,
                                                List.of(TARGET_A),
                                                RATIONALE,
                                                null)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("audience");
                        });
        assertThat(events).isEmpty();
        assertThat(intents).isEmpty();
    }

    @Test
    @DisplayName("rationale 超过 1KB：按 UTF-8 字节而不是字符数计，400 且不落账")
    void oversized_rationale_is_bad_request() {
        DecisionService service = service();
        service.decide(
                executeTool(
                        CONTRACT,
                        CREATE,
                        List.of(TARGET_A),
                        "a".repeat(DecisionService.MAX_RATIONALE_BYTES),
                        AUDIENCE));
        // 341 个汉字 = 1023 字节：字符数远小于 1024，但按字节也没超，必须允许
        service.decide(executeTool(CONTRACT, CREATE, List.of(TARGET_A), "票".repeat(341), AUDIENCE));
        assertThat(events).containsExactly("intent", "sign", "intent", "sign");

        assertThatThrownBy(
                        () ->
                                service.decide(
                                        executeTool(
                                                CONTRACT,
                                                CREATE,
                                                List.of(TARGET_A),
                                                "a".repeat(DecisionService.MAX_RATIONALE_BYTES + 1),
                                                AUDIENCE)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason())
                                    .contains(String.valueOf(DecisionService.MAX_RATIONALE_BYTES));
                        });
        // 342 个汉字 = 1026 字节：字符数只有 342，按字节计超限——证明上限是 UTF-8 字节
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        executeTool(
                                                CONTRACT,
                                                CREATE,
                                                List.of(TARGET_A),
                                                "票".repeat(342),
                                                AUDIENCE)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("1026 字节");
                        });
        assertThat(events).containsExactly("intent", "sign", "intent", "sign");
    }

    @Test
    @DisplayName("deny 的 intent 带注册表声明的动作分类")
    void denied_contract_command_still_records_intent() {
        DecisionService.DecisionResult result = service().decide(executeTool(DELETE));

        assertThat(result.verdict()).isEqualTo("deny");
        assertThat(result.denyRule()).isEqualTo("CONTRACT_CAPABILITY");
        assertThat(intents)
                .singleElement()
                .extracting(DecisionIntent::actionClass)
                .isEqualTo(ActionClass.COMPENSABLE);
    }

    @Test
    @DisplayName("只读决策写 intent + outcome(not_applicable)，不签 token")
    void read_decision_writes_no_token() {
        DecisionService.DecisionResult result = service().decide(readDecision(GET));

        assertThat(result.verdict()).isEqualTo("allow");
        assertThat(result.denyRule()).isNull();
        assertThat(result.token()).isNull();
        assertThat(result.expiresAt()).isNull();
        assertThat(events).containsExactly("intent", "outcome");

        List<DecisionRecord> records = ledger.byDecision(TENANT, result.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        assertThat(records.get(1).body()).containsEntry("source", "plane_decided");
        assertThat(effectOf(records.get(1))).containsEntry("status", "not_applicable");
        assertThat(intents).singleElement().extracting(DecisionIntent::actionClass).isNull();
    }

    @Test
    @DisplayName("请求里的 args_hash 被忽略：指纹按 payload 服务端重算")
    void plane_recomputes_args_hash_from_fingerprint() {
        String json =
                """
                {"task_contract_id": "%s", "conversation_id": "%s", "step": 0,
                 "kind": "execute_tool",
                 "command": {"type": "ticket.create", "payload": {"amount": "128.00"},
                   "targets": ["%s"], "expected_version": 7, "args_hash": "sha256:forged"},
                 "rationale": "为工单开票", "evidence": [],
                 "model": {"name": "gpt", "version": "2026-11"},
                 "audience": "ticket-service", "args_hash": "sha256:forged"}
                """
                        .formatted(CONTRACT, CONVERSATION, TARGET_A);

        DecisionService.DecisionResult result =
                service().decide(JSON.readValue(json, DecisionService.DecisionRequest.class));

        String expected =
                ArgsHash.of(
                        new CommandFingerprint(
                                CREATE, Map.of("amount", "128.00"), List.of(TARGET_A), 7L));
        assertThat(expected).isNotEqualTo("sha256:forged");
        assertThat(result.token()).isNotNull();
        assertThat(signed)
                .singleElement()
                .extracting(DecisionTokenClaims::argsHash)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("身份只来自配置：请求里的 actor/on_behalf_of/tenant 被忽略")
    void identity_comes_from_config_not_request() {
        String json =
                """
                {"task_contract_id": "%s", "conversation_id": "%s", "step": 0,
                 "kind": "execute_tool",
                 "command": {"type": "ticket.create", "payload": {"amount": "128.00"},
                   "targets": ["%s"], "expected_version": 7},
                 "rationale": "为工单开票", "evidence": [],
                 "model": {"name": "gpt", "version": "2026-11"},
                 "audience": "ticket-service", "actor": "%s", "on_behalf_of": "%s",
                 "tenant": "t_other"}
                """
                        .formatted(CONTRACT, CONVERSATION, TARGET_A, OTHER_ACTOR, OTHER_USER);

        service().decide(JSON.readValue(json, DecisionService.DecisionRequest.class));

        assertThat(signed)
                .singleElement()
                .satisfies(
                        claims -> {
                            assertThat(claims.actor()).isEqualTo(ACTOR);
                            assertThat(claims.onBehalfOf()).isEqualTo(ON_BEHALF_OF);
                            assertThat(claims.tenant()).isEqualTo(TENANT.toString());
                        });
        assertThat(intents)
                .singleElement()
                .satisfies(
                        intent -> {
                            assertThat(intent.identity().actor()).isEqualTo(ACTOR);
                            assertThat(intent.identity().onBehalfOf()).isEqualTo(ON_BEHALF_OF);
                            assertThat(intent.tenantId()).isEqualTo(TENANT);
                        });
    }

    @Test
    @DisplayName("同一 payload 的 args_hash 稳定，decision_id 每次新生成")
    void same_payload_yields_stable_args_hash() {
        DecisionService service = service();

        service.decide(executeTool(CREATE));
        service.decide(executeTool(CREATE));

        assertThat(signed).hasSize(2);
        assertThat(signed.get(0).argsHash()).isEqualTo(signed.get(1).argsHash());
        assertThat(signed.get(0).decisionId()).isNotEqualTo(signed.get(1).decisionId());
    }

    @Test
    @DisplayName("targets 顺序变化即换 args_hash")
    void different_target_order_changes_args_hash() {
        DecisionService service = service();

        service.decide(executeTool(CREATE));
        service.decide(executeTool(CREATE, List.of(TARGET_B, TARGET_A)));

        assertThat(signed.get(0).argsHash()).isNotEqualTo(signed.get(1).argsHash());
    }

    @Test
    @DisplayName("claim 的 ledger_intent_id 就是账本 INTENT 记录的 record_id")
    void claims_ledger_intent_id_points_at_intent_record() {
        DecisionService.DecisionResult result = service().decide(executeTool(CREATE));

        DecisionRecord intent =
                ledger.byDecision(TENANT, result.decisionId()).stream()
                        .filter(record -> record.kind() == DecisionRecordKind.INTENT)
                        .findFirst()
                        .orElseThrow();
        assertThat(signed)
                .singleElement()
                .extracting(DecisionTokenClaims::ledgerIntentId)
                .isEqualTo(intent.recordId());
        assertThat(result.intentRecordId()).isEqualTo(intent.recordId());
    }

    @Test
    @DisplayName("decision_id 来自注入的 IdGenerator")
    void decision_id_comes_from_the_injected_generator() {
        DecisionService service = service(recordingSigner(), () -> FIXED_DECISION_ID, AUDIENCE);

        DecisionService.DecisionResult result = service.decide(executeTool(CREATE));

        assertThat(result.decisionId()).isEqualTo(FIXED_DECISION_ID);
        assertThat(intents)
                .singleElement()
                .extracting(DecisionIntent::decisionId)
                .isEqualTo(FIXED_DECISION_ID);
        assertThat(signed)
                .singleElement()
                .extracting(DecisionTokenClaims::decisionId)
                .isEqualTo(FIXED_DECISION_ID);
    }

    @Test
    @DisplayName("账本 args 不落原始 payload：脱敏未实现时留空")
    void ledger_args_never_carry_the_raw_payload() {
        DecisionService.DecisionResult result = service().decide(executeTool(CREATE));

        List<DecisionRecord> records = ledger.byDecision(TENANT, result.decisionId());
        assertThat(records.get(0).body()).doesNotContainKey("args");
        assertThat(records.get(0).body().toString()).doesNotContain("128.00");
    }

    @Test
    @DisplayName("未知任务契约：deny CONTRACT_UNKNOWN，仍写 intent + outcome")
    void unknown_task_contract_is_denied() {
        DecisionService.DecisionResult result =
                service().decide(executeTool(UNKNOWN_CONTRACT, CREATE));

        assertThat(result.verdict()).isEqualTo("deny");
        assertThat(result.denyRule()).isEqualTo("CONTRACT_UNKNOWN");
        assertThat(result.token()).isNull();
        List<DecisionRecord> records = ledger.byDecision(TENANT, result.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        assertThat(effectOf(records.get(1))).containsEntry("error_code", "CONTRACT_UNKNOWN");
    }

    @Test
    @DisplayName("契约声明未登记的命令类型时启动失败并点名类型")
    void unregistered_contract_command_fails_startup() {
        assertThatThrownBy(
                        () ->
                                new CapabilityRegistry(
                                        List.of(
                                                new AiProperties.Contract(
                                                        CONTRACT.toString(),
                                                        List.of(UNREGISTERED))),
                                        tools))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UNREGISTERED);
    }

    @Test
    @DisplayName("HTTP 线格式：POST 返回 snake_case，GET 回读记录，未知决策 404")
    void decision_endpoints_use_snake_case_wire() throws Exception {
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new DecisionController(
                                        service(),
                                        null,
                                        () -> Optional.of(permittedCaller()),
                                        AiProperties.DEFAULT_PLANE_PERMISSION))
                        .setControllerAdvice(new ContractErrorHandler())
                        .build();

        String body = JSON.writeValueAsString(executeTool(CREATE));
        String response =
                mvc.perform(
                                post("/v1/decisions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.verdict").value("allow"))
                        .andExpect(jsonPath("$.decision_id").isNotEmpty())
                        .andExpect(jsonPath("$.intent_record_id").isNotEmpty())
                        .andExpect(jsonPath("$.expires_at").value("2026-11-17T09:46:00Z"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(response).contains("\"deny_rule\":null");
        String decisionId = JSON.readTree(response).get("decision_id").asString();

        mvc.perform(get("/v1/decisions/" + decisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision_id").value(decisionId))
                .andExpect(jsonPath("$.records[0].kind").value("intent"))
                .andExpect(jsonPath("$.records[0].record_id").isNotEmpty())
                .andExpect(jsonPath("$.records[0].recorded_at").isNotEmpty());

        mvc.perform(get("/v1/decisions/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("缺 seedj.ai.plane.tenant 时决策面启动失败并点名配置键")
    void missing_plane_tenant_fails_startup() {
        new WebApplicationContextRunner()
                .withUserConfiguration(PlaneHost.class)
                .withConfiguration(AutoConfigurations.of(DecisionPlaneAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.ai.plane.enabled=true",
                        "seedj.ai.identity.actor=" + ACTOR,
                        "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(throwableMessages(context.getStartupFailure()))
                                    .contains("seedj.ai.plane.tenant");
                        });
    }

    @Test
    @DisplayName("JWKS 端点只在决策面打开时装配")
    void jwks_document_is_served_only_when_plane_enabled() {
        WebApplicationContextRunner runner =
                new WebApplicationContextRunner()
                        .withUserConfiguration(PlaneHost.class)
                        .withConfiguration(
                                AutoConfigurations.of(DecisionPlaneAutoConfiguration.class))
                        .withPropertyValues(
                                "seedj.ai.plane.tenant=" + TENANT,
                                "seedj.ai.identity.actor=" + ACTOR,
                                "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF,
                                "seedj.ai.decision.signing-key=" + SEED,
                                "seedj.ai.decision.kid=" + KID);

        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JwksController.class);
                    assertThat(context).doesNotHaveBean(DecisionService.class);
                });

        runner.withPropertyValues("seedj.ai.plane.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(JwksController.class);
                            assertThat(context).hasSingleBean(DecisionService.class);
                            MockMvcBuilders.standaloneSetup(context.getBean(JwksController.class))
                                    .build()
                                    .perform(get(JwksController.JWKS_PATH))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.keys[0].kid").value(KID));
                        });
    }

    @Test
    @DisplayName("多个 IdGenerator<UUID>：不阻断启动，decision_id 回退随机 UUID（v4）")
    void multiple_id_generators_fall_back_to_random_uuid() {
        new WebApplicationContextRunner()
                .withUserConfiguration(PlaneHost.class, TwoIdGeneratorsHost.class)
                .withConfiguration(AutoConfigurations.of(DecisionPlaneAutoConfiguration.class))
                .withPropertyValues(
                        "seedj.ai.plane.enabled=true",
                        "seedj.ai.plane.tenant=" + TENANT,
                        "seedj.ai.identity.actor=" + ACTOR,
                        "seedj.ai.identity.on-behalf-of=" + ON_BEHALF_OF,
                        "seedj.ai.decision.signing-key=" + SEED,
                        "seedj.ai.decision.kid=" + KID)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            DecisionService.DecisionResult result =
                                    context.getBean(DecisionService.class)
                                            .decide(readDecision(GET));
                            // 两个候选都不是随机 UUID：返回 v4 证明走的是回退分支
                            assertThat(result.decisionId().version()).isEqualTo(4);
                        });
    }


    @Test
    @DisplayName("策略引用同时进 intent 与 token：账本与凭据钉的是同一个版本")
    void policy_ref_is_in_intent_and_token() {
        PolicyRef pinned = new PolicyRef("local", "ticket-policy", 3L, 41L, "allow", List.of(), null);
        DecisionService service = serviceWithPolicy(request -> pinned);

        DecisionService.DecisionResult result = service.decide(executeTool(CREATE));

        assertThat(result.verdict()).isEqualTo(DecisionService.VERDICT_ALLOW);
        assertThat(result.token()).isNotNull();
        assertThat(intents).singleElement().extracting(DecisionIntent::policy).isEqualTo(pinned);
        assertThat(signed).singleElement().extracting(DecisionTokenClaims::policy).isEqualTo(pinned);
    }

    @Test
    @DisplayName("策略结论不是 allow：先落 intent，再 outcome(rejected)，不签 token")
    void policy_deny_writes_intent_and_rejects() {
        PolicyRef denied =
                new PolicyRef("local", "ticket-policy", 3L, 41L, "deny", List.of("r-7"), null);
        DecisionService service = serviceWithPolicy(request -> denied);

        DecisionService.DecisionResult result = service.decide(executeTool(CREATE));

        assertThat(result.verdict()).isEqualTo(DecisionService.VERDICT_DENY);
        assertThat(result.denyRule()).isEqualTo(DecisionService.RULE_POLICY);
        assertThat(result.token()).isNull();
        assertThat(events).containsExactly("intent", "outcome");
        List<DecisionRecord> records = ledger.byDecision(TENANT, result.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        assertThat(effectOf(records.get(1)))
                .containsEntry("status", "rejected")
                .containsEntry("error_code", DecisionService.RULE_POLICY);
        assertThat(signed).isEmpty();
    }

    @Test
    @DisplayName("策略求值失败：不落账、不签发——求值失败不是决策")
    void policy_failure_leaves_no_trace() {
        DecisionService service =
                serviceWithPolicy(
                        request -> {
                            throw new IllegalStateException("policy engine down");
                        });

        assertThatThrownBy(() -> service.decide(executeTool(CREATE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy engine down");

        assertThat(events).isEmpty();
        assertThat(intents).isEmpty();
        assertThat(signed).isEmpty();
    }

    @Test
    @DisplayName("未启用策略：引用仍是 none()，与 T0 逐字一致")
    void disabled_policy_keeps_t0_none_ref() {
        DecisionService service = service();

        DecisionService.DecisionResult result = service.decide(executeTool(CREATE));

        assertThat(intents).singleElement().extracting(DecisionIntent::policy).isEqualTo(PolicyRef.none());
        assertThat(signed).singleElement().extracting(DecisionTokenClaims::policy).isEqualTo(PolicyRef.none());
        assertThat(result.token()).isNotNull();
    }

    /** 带决策面权限的调用方：`/v1/decisions` 现在既认证也授权。 */
    private static Caller permittedCaller() {
        return new Caller(
                ACTOR.toString(),
                "agent-runtime",
                TENANT.toString(),
                Set.of("agent"),
                Set.of(AiProperties.DEFAULT_PLANE_PERMISSION),
                Set.of(),
                Set.of(),
                null,
                null);
    }

    private DecisionService service() {
        return service(recordingSigner());
    }

    private DecisionService service(DecisionService.Signer signer) {
        return service(signer, UUID::randomUUID, AUDIENCE);
    }

    private DecisionService service(
            DecisionService.Signer signer, IdGenerator<UUID> ids, String audience) {
        return new DecisionService(
                capabilities,
                tools,
                recordingLedger(),
                signer,
                ids,
                () -> NOW,
                properties(audience));
    }

    private DecisionService serviceWithPolicy(PolicyEvaluator policy) {
        return new DecisionService(
                capabilities,
                tools,
                recordingLedger(),
                recordingSigner(),
                UUID::randomUUID,
                () -> NOW,
                properties(AUDIENCE),
                policy);
    }

    private DecisionService.Signer recordingSigner() {
        return claims -> {
            events.add("sign");
            signed.add(claims);
            return "jws-" + claims.decisionId();
        };
    }

    private DecisionLedger recordingLedger() {
        return new DecisionLedger() {

            @Override
            public DecisionRecord appendIntent(DecisionIntent intent) {
                events.add("intent");
                intents.add(intent);
                return ledger.appendIntent(intent);
            }

            @Override
            public DecisionRecord appendApproval(UUID decisionId, ApprovalSnapshot snapshot) {
                return ledger.appendApproval(decisionId, snapshot);
            }

            @Override
            public DecisionRecord appendOutcome(
                    UUID decisionId, DecisionEffect effect, OutcomeSource source) {
                events.add("outcome");
                return ledger.appendOutcome(decisionId, effect, source);
            }

            @Override
            public DecisionRecord appendReconciled(
                    UUID decisionId, DecisionEffect effect, String reason) {
                return ledger.appendReconciled(decisionId, effect, reason);
            }

            @Override
            public Optional<DecisionRecord> find(UUID tenantId, UUID recordId) {
                return ledger.find(tenantId, recordId);
            }

            @Override
            public List<DecisionRecord> byDecision(UUID tenantId, UUID decisionId) {
                return ledger.byDecision(tenantId, decisionId);
            }

            @Override
            public LedgerVerification verify(String partition) {
                return ledger.verify(partition);
            }
        };
    }

    private static ToolRegistry registry() {
        return new ConfiguredToolRegistry(
                List.of(
                        new AgentToolSpec(CREATE, ActionClass.REVERSIBLE),
                        new AgentToolSpec(DELETE, ActionClass.COMPENSABLE),
                        new AgentToolSpec(GET, ActionClass.READ_ONLY)));
    }

    private CapabilityRegistry capabilities() {
        return new CapabilityRegistry(
                List.of(new AiProperties.Contract(CONTRACT.toString(), List.of(CREATE, GET))),
                tools);
    }

    private static AiProperties properties(String audience) {
        return new AiProperties(
                true,
                new AiProperties.Plane(
                        true,
                        TENANT,
                        AiProperties.DEFAULT_PLANE_PERMISSION,
                        null,
                        Duration.ofMinutes(10)),
                null,
                null,
                new AiProperties.Identity(ACTOR.toString(), ON_BEHALF_OF.toString()),
                new AiProperties.Decision(SEED, KID, TTL, audience),
                List.of(),
                List.of());
    }

    private static DecisionService.DecisionRequest executeTool(String commandType) {
        return executeTool(CONTRACT, commandType);
    }

    private static DecisionService.DecisionRequest executeTool(
            String commandType, List<UUID> targets) {
        return executeTool(CONTRACT, commandType, targets);
    }

    private static DecisionService.DecisionRequest executeTool(
            UUID taskContractId, String commandType) {
        return executeTool(taskContractId, commandType, List.of(TARGET_A, TARGET_B));
    }

    private static DecisionService.DecisionRequest executeTool(
            UUID taskContractId, String commandType, List<UUID> targets) {
        return executeTool(taskContractId, commandType, targets, RATIONALE, AUDIENCE);
    }

    private static DecisionService.DecisionRequest executeTool(
            UUID taskContractId,
            String commandType,
            List<UUID> targets,
            String rationale,
            String audience) {
        return new DecisionService.DecisionRequest(
                taskContractId,
                CONVERSATION,
                0,
                "execute_tool",
                new DecisionService.Command(commandType, PAYLOAD, targets, EXPECTED_VERSION),
                rationale,
                List.of(new Evidence("message", "im://msg/1", "sha256:abc")),
                new DecisionService.Model("gpt", "2026-11", "prompt-3", "sha256:params"),
                audience);
    }

    private static DecisionService.DecisionRequest readDecision(String commandType) {
        return new DecisionService.DecisionRequest(
                CONTRACT,
                CONVERSATION,
                1,
                "read",
                new DecisionService.Command(commandType, Map.of(), List.of(TARGET_A), null),
                "读工单详情",
                List.of(),
                new DecisionService.Model("gpt", "2026-11", null, null),
                null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectOf(DecisionRecord record) {
        return (Map<String, Object>) record.body().get("effect");
    }

    private static String throwableMessages(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = error; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return String.join(" | ", messages);
    }

    /** 装配期用例的宿主：只给标记、时钟与账本，其余 bean 由决策面配置自己装配。 */
    @Configuration(proxyBeanMethods = false)
    static class PlaneHost {

        @Bean
        AiEnabledMarker aiEnabledMarker() {
            return new AiEnabledMarker();
        }

        @Bean
        Clock clock() {
            return () -> NOW;
        }

        @Bean
        DecisionLedger decisionLedger() {
            return new InMemoryDecisionLedger();
        }
    }

    /** 两个 {@code IdGenerator<UUID>} 的宿主：本仓常见的多 id 生成器装配，不能因此启动失败。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoIdGeneratorsHost {

        @Bean(name = "commandIdGenerator")
        IdGenerator<UUID> commandIdGenerator() {
            return () -> UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
        }

        @Bean(name = "hrCommandIds")
        IdGenerator<UUID> hrCommandIds() {
            return () -> UUID.fromString("018f2e3a-0000-7000-8000-000000000002");
        }
    }
}
