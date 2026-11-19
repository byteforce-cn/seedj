package cn.byteforce.seedj.it.ai;

import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.AGENT_ID;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.AUDIENCE;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.CONTRACT_ID;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.TENANT;
import static cn.byteforce.seedj.it.ai.PilotTestConfiguration.USER_ID;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.ai.ActionClass;
import cn.byteforce.seedj.ai.CommandRef;
import cn.byteforce.seedj.ai.DecisionKind;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.LedgerVerification;
import cn.byteforce.seedj.ai.Proposal;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.starter.ai.runtime.BoundedLoop;
import cn.byteforce.seedj.starter.ai.runtime.DecisionClient;
import cn.byteforce.seedj.starter.ai.runtime.DecisionGrant;
import cn.byteforce.seedj.starter.ai.runtime.HttpDecisionClient;
import cn.byteforce.seedj.starter.ai.runtime.HttpToolInvoker;
import cn.byteforce.seedj.starter.ai.runtime.LoopResult;
import cn.byteforce.seedj.starter.ai.runtime.TaskContract;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 13 的 T0 退出判据：<b>每个写动作都能用「intent + command + audit entry + reconciled」四件套还原</b>。
 *
 * <p>四条用例分别钉四件事：agent 循环真的能驱动一次写（脚本 LLM → 决策面 → guard → seedj 命令）、一次受理的命令在账本/ 业务库/审计链/对账里能串成一条链、同一
 * {@code decision_id} 重复提交只产生一次副作用、{@code writeOnly} 字段既不破坏 守卫（{@code args_hash} 用原文算）也不出现在读回的视图里。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@Testcontainers
@SpringBootTest(
        classes = PilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"pilot", "test"})
class AiPilotIT extends AbstractPilotIT {

    private static final Logger LOG = LoggerFactory.getLogger(AiPilotIT.class);

    @Container static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registerDatasource(registry, POSTGRES);
    }

    @Test
    @DisplayName("脚本化 LLM → 决策面 → guard → 命令：一次写动作端到端跑通并落库")
    void runtime_drives_write_end_to_end() {
        String title = "runtime-" + UUID.randomUUID();
        Proposal create =
                new Proposal(
                        DecisionKind.EXECUTE_TOOL,
                        ActionClass.COMPENSABLE,
                        new CommandRef("ticket.create", List.of(), null),
                        Map.of("title", title, "status", "open"),
                        "为本次故障建一张工单，便于后续追踪",
                        List.of());
        RecordingDecisionClient decisions =
                new RecordingDecisionClient(
                        new HttpDecisionClient(RestClient.builder(), baseUrl(), pilotModel()));
        BoundedLoop loop =
                new BoundedLoop(
                        ScriptedLlmGateway.of(create, ScriptedLlmGateway.finish()),
                        decisions,
                        new HttpToolInvoker(RestClient.builder(), baseUrl()),
                        4,
                        AUDIENCE);

        LoopResult result =
                loop.run(new TaskContract(CONTRACT_ID, UUID.randomUUID(), "记录一次故障工单", 4));

        assertThat(result.outcome()).isEqualTo(LoopResult.Outcome.FINISHED);
        assertThat(result.steps()).isEqualTo(2);
        TemporalRow row = objects.current("ticket", objectIdOf(title)).orElseThrow();
        assertThat(row.fields()).containsEntry("title", title).containsEntry("status", "open");

        // runtime 自己上报了 outcome：用例不再手工调 /outcome，账本里却已经补齐 intent + outcome。
        UUID commandId =
                jdbc.queryForObject(
                        "select id from command_record where payload->>'title' = ?",
                        UUID.class,
                        title);
        List<DecisionRecord> records = ledger.byDecision(TENANT, decisions.decisionId());
        assertThat(records)
                .extracting(DecisionRecord::kind)
                .containsExactly(DecisionRecordKind.INTENT, DecisionRecordKind.OUTCOME);
        DecisionRecord outcome = records.get(1);
        assertThat(outcome.body())
                .as("来源是 runtime 上报，不是决策面自己写的结论")
                .containsEntry("source", "runtime_reported");
        Map<?, ?> effect = (Map<?, ?>) outcome.body().get("effect");
        assertThat((Object) effect.get("status")).as("命令在库里是 accepted").isEqualTo("ok");
        assertThat(String.valueOf(effect.get("command_id"))).isEqualTo(commandId.toString());
        assertThat((Object) effect.get("audit_entry_id")).as("上报阶段不写链目标识：那是 reconcile 的事").isNull();
    }

    @Test
    @DisplayName("受理的命令有四件套可回放：intent + command + audit entry + reconciled")
    void accepted_command_has_four_piece_replay() {
        String title = "replay-" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("status", "open");
        payload.put("assignee", USER_ID.toString());

        Decision decision = allowExecute("ticket.create", payload, List.of(), null);
        ResponseEntity<String> accepted =
                submit(
                        "ticket.create",
                        payload,
                        List.of(),
                        null,
                        decision.decisionId(),
                        decision.token());
        assertThat(accepted.getStatusCode().value())
                .as("body=%s", accepted.getBody())
                .isEqualTo(202);
        UUID commandId = UUID.fromString(JsonPath.read(accepted.getBody(), "$.command_id"));

        // 1) 账本：intent 先落账，带 args_hash 与 idem_fingerprint
        DecisionRecord intent =
                ledgerRecords(decision.decisionId()).stream()
                        .filter(record -> record.kind() == DecisionRecordKind.INTENT)
                        .findFirst()
                        .orElseThrow();
        assertThat(intent.body().get("args_hash"))
                .as("args_hash 覆盖 {type,payload,targets,expectedVersion}")
                .isInstanceOf(String.class);
        assertThat((String) intent.body().get("args_hash")).startsWith("sha256:");
        assertThat((String) intent.body().get("idem_fingerprint"))
                .as("幂等指纹 64 位小写十六进制")
                .hasSize(64);

        // 2) 业务库：幂等键 = decision_id 的命令记录，状态 accepted
        Map<String, Object> command =
                jdbc.queryForMap(
                        "select id, status, locator from command_record"
                                + " where issuer = ? and idempotency_key = ?",
                        AGENT_ID,
                        decision.decisionId().toString());
        assertThat(command.get("id")).isEqualTo(commandId);
        assertThat(command.get("status")).isEqualTo("accepted");

        // 3) 审计链：按 payload->>'command_id' 找到那一条（U9 前的唯一关联方式）
        String auditEntryId =
                jdbc.queryForObject(
                        "select id from audit_entry where payload->>'command_id' = ?",
                        String.class,
                        commandId.toString());
        assertThat(auditEntryId).isNotBlank();
        assertThat(
                        jdbc.queryForObject(
                                "select actor from audit_entry where id = ?",
                                String.class,
                                auditEntryId))
                .as("链条目 actor 是 agent principal（spec §6.3）")
                .isEqualTo(AGENT_ID.toString());

        // 4) 上报 outcome 后对账：补一条 reconciled，把 audit_entry_id 串上
        ResponseEntity<String> reported = reportOutcome(decision.decisionId(), commandId);
        assertThat(reported.getStatusCode().value()).isEqualTo(200);
        assertThat((String) JsonPath.read(reported.getBody(), "$.status")).isEqualTo("ok");
        Optional<DecisionRecord> reconciled = reconciliation.reconcile(decision.decisionId());
        assertThat(reconciled).isPresent();
        assertThat(reconciled.get().kind()).isEqualTo(DecisionRecordKind.RECONCILED);
        Map<?, ?> effect = (Map<?, ?>) reconciled.get().body().get("effect");
        assertThat(effect.get("status")).isEqualTo("ok");
        assertThat(effect.get("audit_entry_id")).isEqualTo(auditEntryId);
        assertThat(effect.get("command_id")).isEqualTo(commandId.toString());

        LOG.info(
                "four-piece: decision={} intent={} command={} audit={} reconciled={}",
                decision.decisionId(),
                intent.recordId(),
                commandId,
                auditEntryId,
                reconciled.get().recordId());

        // 账本自身的哈希链必须自洽
        assertThat(ledger.verify(DecisionRecord.partitionOf(TENANT)))
                .as("账本分区内的哈希链必须自洽")
                .matches(LedgerVerification::ok);
    }

    @Test
    @DisplayName("同一 decision_id 提交两次：第二次返回首次结果，命令与链条目各一条")
    void same_decision_id_twice_yields_one_audit_entry() {
        String title = "duplicate-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of("title", title, "status", "open");
        Decision decision = allowExecute("ticket.create", payload, List.of(), null);

        ResponseEntity<String> first =
                submit(
                        "ticket.create",
                        payload,
                        List.of(),
                        null,
                        decision.decisionId(),
                        decision.token());
        ResponseEntity<String> second =
                submit(
                        "ticket.create",
                        payload,
                        List.of(),
                        null,
                        decision.decisionId(),
                        decision.token());

        assertThat(first.getStatusCode().value()).as("body=%s", first.getBody()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).as("body=%s", second.getBody()).isEqualTo(202);
        String commandId = JsonPath.read(first.getBody(), "$.command_id");
        String replayCommandId = JsonPath.read(second.getBody(), "$.command_id");
        assertThat(replayCommandId).isEqualTo(commandId);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from command_record where idempotency_key = ?",
                                Integer.class,
                                decision.decisionId().toString()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from audit_entry where payload->>'command_id' = ?",
                                Integer.class,
                                commandId))
                .isEqualTo(1);
    }

    /** 记下每一步拿到的 {@code decision_id}：runtime 自己上报 outcome 后，用例才能按它查账本。 */
    private static final class RecordingDecisionClient implements DecisionClient {

        private final DecisionClient delegate;

        private UUID decisionId;

        private RecordingDecisionClient(DecisionClient delegate) {
            this.delegate = delegate;
        }

        private UUID decisionId() {
            return decisionId;
        }

        @Override
        public DecisionGrant ask(
                TaskContract contract, int step, Proposal proposal, String audience) {
            DecisionGrant grant = delegate.ask(contract, step, proposal, audience);
            decisionId = grant.decisionId();
            return grant;
        }

        @Override
        public void report(UUID reportedDecision, UUID commandId) {
            delegate.report(reportedDecision, commandId);
        }
    }

    @Test
    @DisplayName("不可逆动作：pending → approve → grant → guard 接受")
    void irreversible_requires_approval_then_grant_and_guard_accepts() {
        String title = "refund-" + UUID.randomUUID();
        Map<String, Object> createPayload = Map.of("title", title, "status", "open");
        Decision created = allowExecute("ticket.create", createPayload, List.of(), null);
        ResponseEntity<String> createdResponse =
                submit(
                        "ticket.create",
                        createPayload,
                        List.of(),
                        null,
                        created.decisionId(),
                        created.token());
        assertThat(createdResponse.getStatusCode().value()).isEqualTo(202);
        UUID ticketId = objectIdOf(title);

        Map<String, Object> refundPayload = new LinkedHashMap<>();
        refundPayload.put("ticket_id", ticketId.toString());
        refundPayload.put("amount_cents", 12800);
        ResponseEntity<String> pending =
                postJson(
                        "/v1/decisions",
                        json(
                                decisionRequest(
                                        "ticket.refund", refundPayload, List.of(ticketId), null)));
        assertThat(pending.getStatusCode().value()).as("body=%s", pending.getBody()).isEqualTo(200);
        assertThat((String) JsonPath.read(pending.getBody(), "$.verdict"))
                .isEqualTo("pending_approval");
        assertThat((Object) JsonPath.read(pending.getBody(), "$.token")).isNull();
        UUID decisionId = UUID.fromString(JsonPath.read(pending.getBody(), "$.decision_id"));

        ResponseEntity<String> grantBeforeApproval =
                postJson("/v1/decisions/" + decisionId + "/grant", "{}");
        assertThat(grantBeforeApproval.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> approved =
                postJson(
                        "/v1/decisions/" + decisionId + "/approval",
                        json(Map.of("action", "approve", "comment", "pilot approve")));
        assertThat(approved.getStatusCode().value())
                .as("body=%s", approved.getBody())
                .isEqualTo(200);
        assertThat((String) JsonPath.read(approved.getBody(), "$.status")).isEqualTo("approved");

        ResponseEntity<String> granted = postJson("/v1/decisions/" + decisionId + "/grant", "{}");
        assertThat(granted.getStatusCode().value()).as("body=%s", granted.getBody()).isEqualTo(200);
        String token = JsonPath.read(granted.getBody(), "$.token");
        assertThat(token).isNotBlank();

        ResponseEntity<String> submitted =
                submit("ticket.refund", refundPayload, List.of(ticketId), null, decisionId, token);
        assertThat(submitted.getStatusCode().value())
                .as("body=%s", submitted.getBody())
                .isEqualTo(202);

        assertThat(ledgerRecords(decisionId))
                .extracting(DecisionRecord::kind)
                .contains(DecisionRecordKind.INTENT, DecisionRecordKind.APPROVAL);
        TemporalRow refunded = objects.current("ticket", ticketId).orElseThrow();
        assertThat(refunded.fields().get("last_comment")).isEqualTo("refund 12800");
    }

    @Test
    @DisplayName("writeOnly 字段不影响守卫（哈希用原文），但读回的对象不含它")
    void write_only_field_does_not_break_guard() {
        String title = "write-only-" + UUID.randomUUID();
        String internalNote = "只给审核人看的内部备注";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("status", "open");
        payload.put("internal_note", internalNote);

        Decision decision = allowExecute("ticket.create", payload, List.of(), null);
        ResponseEntity<String> accepted =
                submit(
                        "ticket.create",
                        payload,
                        List.of(),
                        null,
                        decision.decisionId(),
                        decision.token());

        assertThat(accepted.getStatusCode().value())
                .as("guard 用未脱敏原文算 args_hash，writeOnly 不是理由: %s", accepted.getBody())
                .isEqualTo(202);
        UUID ticketId = objectIdOf(title);
        assertThat(objects.current("ticket", ticketId).orElseThrow().fields())
                .as("写入专用字段仍要落库")
                .containsEntry("internal_note", internalNote);

        TemporalRow read =
                reads.find(
                                "ticket",
                                ticketId,
                                TemporalQuery.current(),
                                callers.find().orElseThrow())
                        .orElseThrow();
        assertThat(read.fields())
                .as("读路径排除 writeOnly 字段")
                .doesNotContainKey("internal_note")
                .containsEntry("title", title);

        assertThat(ledger.byDecision(TENANT, decision.decisionId()).get(0).body().toString())
                .as("账本 args 不得落 writeOnly 原文（spec §6.2 只存脱敏后可展示参数）")
                .doesNotContain(internalNote);
    }
}
