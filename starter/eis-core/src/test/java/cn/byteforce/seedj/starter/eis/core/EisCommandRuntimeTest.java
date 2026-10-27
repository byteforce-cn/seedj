package cn.byteforce.seedj.starter.eis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EisCommandRuntime} 受理语义测试（幂等 / 授权 / 规则 / 处理器 / 审计 / outbox）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class EisCommandRuntimeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static final UUID ACTOR = UUID.fromString("01900000-0000-7000-8000-000000000001");

    private static final UUID SUBJECT = UUID.fromString("01900000-0000-7000-8000-0000000000aa");

    private EisCommandStore commandStore;

    private EisEventStore eventStore;

    private EisAuditAppender auditAppender;

    private EisNotifyHook notifyHook;

    private EisCommandRuntime runtime;

    private TestHandler handler;

    @BeforeEach
    void setUp() {
        commandStore = mock(EisCommandStore.class);
        eventStore = mock(EisEventStore.class);
        auditAppender = mock(EisAuditAppender.class);
        notifyHook = mock(EisNotifyHook.class);
        handler = new TestHandler();
    }

    private EisCommandRuntime buildRuntime(EisAuthorizer authorizer, EisRule... rules) {
        EisObjectTypeRegistry registry = new EisObjectTypeRegistry(List.of());
        EisObjectRepository objects = mock(EisObjectRepository.class);
        return new EisCommandRuntime(EisClock.fixed(NOW), registry, objects, commandStore, eventStore,
                auditAppender, authorizer, List.of(handler), List.of(rules), entry -> { }, notifyHook,
                mock(PlatformTransactionManager.class));
    }

    private EisCommandRequest request(String type, String payloadJson, String idemKey) {
        return new EisCommandRequest(type, MAPPER.readTree(payloadJson), idemKey, List.of(), ACTOR);
    }

    @Test
    void acceptedFlowWritesCommandEventAuditAndOutbox() {
        runtime = buildRuntime(null);
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"name\":\"alice\"}", "k1"));

        assertThat(result.getStatus()).isEqualTo("accepted");
        assertThat(result.getEventIds()).hasSize(1);
        assertThat(result.getCreated().getObjectType()).isEqualTo("demo_thing");
        assertThat(result.getCreated().getId()).isEqualTo(SUBJECT);

        verify(commandStore).insertCommand(any(), anyString(), any(), any(), any(), any(), anyString());
        ArgumentCaptor<EisObjectRef> createdCaptor = ArgumentCaptor.forClass(EisObjectRef.class);
        verify(commandStore).markAccepted(any(), any(), createdCaptor.capture());
        assertThat(createdCaptor.getValue().getObjectType()).isEqualTo("demo_thing");
        assertThat(createdCaptor.getValue().getId()).isEqualTo(SUBJECT);
        verify(eventStore).insert(any(EisEvent.class));
        verify(eventStore).insertOutbox(any());
        ArgumentCaptor<EisAuditAppender.EisAuditAppendRequest> auditCaptor =
                ArgumentCaptor.forClass(EisAuditAppender.EisAuditAppendRequest.class);
        verify(auditAppender).append(auditCaptor.capture());
        EisAuditAppender.EisAuditAppendRequest audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo("demo.thing.create");
        assertThat(audit.getResourceType()).isEqualTo("demo");
        assertThat(audit.getResourceId()).isEqualTo(SUBJECT);
        assertThat(audit.getPayload().get("command_id").asString()).isEqualTo(result.getCommandId().toString());
        assertThat(audit.getPayload().get("event_ids")).hasSize(1);
    }

    @Test
    void rejectedByRuleSkipsAuditAndEvents() {
        runtime = buildRuntime(null, new RejectingRule("demo.thing.create", "IAM-403-002", "受保护", "SOD-001"));
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"name\":\"root\"}", "k2"));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getCode()).isEqualTo("IAM-403-002");
        assertThat(result.getReasonCode()).isEqualTo("SOD-001");
        verify(commandStore).markRejected(any(), anyString(), anyString());
        verify(eventStore, never()).insert(any(EisEvent.class));
        verify(auditAppender, never()).append(any());
        assertThat(handler.handled).isFalse();
    }

    @Test
    void rejectedByHandlerIsRecorded() {
        runtime = buildRuntime(null);
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        handler.failing = true;

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"name\":\"x\"}", "k3"));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getCode()).isEqualTo("DEMO-409-001");
        verify(commandStore).markRejected(any(), anyString(), anyString());
        verify(auditAppender, never()).append(any());
    }

    @Test
    void rejectedByHandlerCarriesFieldDetails() {
        runtime = buildRuntime(null);
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        handler.failing = true;
        handler.rejectionDetails = List.of(new EisCommandRejectionDetail("amount", "必须为正数"));

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"amount\":-1}", "k3d"));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getDetails()).extracting(EisCommandRejectionDetail::getField).containsExactly("amount");
        assertThat(result.getDetails().get(0).getMessage()).isEqualTo("必须为正数");
    }

    @Test
    void unknownCommandTypeIsRejected() {
        runtime = buildRuntime(null);
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        EisCommandResult result = runtime.submit(request("demo.unknown.op", "{}", "k4"));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getCode()).isEqualTo("EIS-400-001");
        assertThat(handler.handled).isFalse();
    }

    @Test
    void authorizationDeniedIsRecordedWithReasonCode() {
        runtime = buildRuntime(request -> EisAuthorizer.EisAuthorizationDecision.denied("缺少权限", "NO_PERMISSION"));
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"name\":\"x\"}", "k5"));

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.getCode()).isEqualTo("EIS-403-001");
        assertThat(result.getReason()).isEqualTo("缺少权限");
        assertThat(result.getReasonCode()).isEqualTo("NO_PERMISSION");
        verify(commandStore).markRejected(any(), org.mockito.ArgumentMatchers.eq("EIS-403-001"),
                org.mockito.ArgumentMatchers.eq("缺少权限"));
        assertThat(handler.handled).isFalse();
    }

    @Test
    void identicalReplayReturnsOriginalResult() {
        runtime = buildRuntime(null);
        EisCommandRequest request = request("demo.thing.create", "{\"name\":\"alice\"}", "k6");
        String fingerprint = EisCommandRuntime.computeFingerprint(request);
        UUID originalId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(commandStore.findIdempotency("k6"))
                .thenReturn(Optional.of(newIdempotencyRecord(fingerprint, originalId)));
        when(commandStore.findCommand(originalId)).thenReturn(Optional.of(acceptedCommand(originalId, eventId)));

        EisCommandResult result = runtime.submit(request);

        assertThat(result.getStatus()).isEqualTo("accepted");
        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getCommandId()).isEqualTo(originalId);
        assertThat(result.getEventIds()).containsExactly(eventId);
        verify(commandStore, never()).insertCommand(any(), anyString(), any(), any(), any(), any(), anyString());
    }

    @Test
    void identicalReplayReturnsOriginalCreatedRef() {
        runtime = buildRuntime(null);
        EisCommandRequest request = request("demo.thing.create", "{\"name\":\"alice\"}", "k6c");
        String fingerprint = EisCommandRuntime.computeFingerprint(request);
        UUID originalId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EisObjectRef created = new EisObjectRef("demo_thing", SUBJECT);
        when(commandStore.findIdempotency("k6c"))
                .thenReturn(Optional.of(newIdempotencyRecord(fingerprint, originalId)));
        when(commandStore.findCommand(originalId))
                .thenReturn(Optional.of(acceptedCommandWithCreated(originalId, eventId, created)));

        EisCommandResult result = runtime.submit(request);

        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getCreated()).isNotNull();
        assertThat(result.getCreated().getObjectType()).isEqualTo(created.getObjectType());
        assertThat(result.getCreated().getId()).isEqualTo(created.getId());
    }

    @Test
    void identicalReplayOfRejectedReturnsRejection() {
        runtime = buildRuntime(null);
        EisCommandRequest request = request("demo.thing.create", "{\"name\":\"alice\"}", "k7");
        String fingerprint = EisCommandRuntime.computeFingerprint(request);
        UUID originalId = UUID.randomUUID();
        when(commandStore.findIdempotency("k7"))
                .thenReturn(Optional.of(newIdempotencyRecord(fingerprint, originalId)));
        when(commandStore.findCommand(originalId)).thenReturn(Optional.of(rejectedCommand(originalId)));

        EisCommandResult result = runtime.submit(request);

        assertThat(result.getStatus()).isEqualTo("rejected");
        assertThat(result.isReplayed()).isTrue();
        assertThat(result.getCode()).isEqualTo("IAM-409-001");
        assertThat(result.getReason()).isEqualTo("用户名已存在: alice");
    }

    @Test
    void differentContentUnderSameKeyIsConflict() {
        runtime = buildRuntime(null);
        UUID originalId = UUID.randomUUID();
        when(commandStore.findIdempotency("k8"))
                .thenReturn(Optional.of(newIdempotencyRecord("other-fingerprint", originalId)));

        EisCommandResult result = runtime.submit(request("demo.thing.create", "{\"name\":\"bob\"}", "k8"));

        assertThat(result.getStatus()).isEqualTo("conflict");
        assertThat(result.getCode()).isEqualTo("EIS-409-001");
        assertThat(result.getCommandId()).isEqualTo(originalId);
        verify(commandStore, never()).insertCommand(any(), anyString(), any(), any(), any(), any(), anyString());
    }

    @Test
    void concurrentReservationRaceResolvesAsReplay() {
        runtime = buildRuntime(null);
        EisCommandRequest request = request("demo.thing.create", "{\"name\":\"alice\"}", "k9");
        String fingerprint = EisCommandRuntime.computeFingerprint(request);
        UUID originalId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(commandStore.findIdempotency("k9"))
                .thenReturn(Optional.of(newIdempotencyRecord(fingerprint, originalId)));
        when(commandStore.findCommand(originalId)).thenReturn(Optional.of(acceptedCommand(originalId, eventId)));

        EisCommandResult result = runtime.submit(request);

        assertThat(result.isReplayed()).isTrue();
    }

    @Test
    void validationFailuresRaiseRequestExceptions() {
        runtime = buildRuntime(null);

        assertThatThrownBy(() -> runtime.submit(request("Bad Type", "{}", "k")))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-400-001"));
        assertThatThrownBy(() -> runtime.submit(new EisCommandRequest("demo.thing.create",
                MAPPER.readTree("[1,2]"), "k", List.of(), ACTOR)))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> runtime.submit(new EisCommandRequest("demo.thing.create",
                MAPPER.readTree("{}"), "  ", List.of(), ACTOR)))
                .isInstanceOf(EisRequestException.class);
        assertThatThrownBy(() -> runtime.submit(new EisCommandRequest("demo.thing.create",
                MAPPER.readTree("{}"), "k", List.of(), null)))
                .isInstanceOf(EisRequestException.class)
                .satisfies(ex -> assertThat(((EisRequestException) ex).getCode()).isEqualTo("EIS-401-001"));
        assertThatThrownBy(() -> runtime.submit(null)).isInstanceOf(EisRequestException.class);
    }

    @Test
    void storedPayloadRedactionIsApplied() {
        runtime = buildRuntime(null);
        handler.redact = true;
        when(commandStore.insertIdempotencyIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        runtime.submit(request("demo.thing.create", "{\"name\":\"alice\",\"secret\":\"s3cr3t\"}", "k10"));

        ArgumentCaptor<JsonNode> payloadCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(commandStore).insertCommand(any(), anyString(), any(), any(), any(), payloadCaptor.capture(),
                anyString());
        assertThat(payloadCaptor.getValue().has("secret")).isFalse();
        assertThat(payloadCaptor.getValue().get("name").asString()).isEqualTo("alice");
    }

    @Test
    void findCommandDelegatesToStore() {
        runtime = buildRuntime(null);
        UUID id = UUID.randomUUID();
        when(commandStore.findCommand(id)).thenReturn(Optional.of(acceptedCommand(id, UUID.randomUUID())));

        assertThat(runtime.findCommand(id)).isPresent();
    }

    private static EisCommandStore.EisIdempotencyRecord newIdempotencyRecord(String fingerprint, UUID commandId) {
        return new EisCommandStore.EisIdempotencyRecord(fingerprint, commandId);
    }

    private static EisCommandStore.EisStoredCommand acceptedCommand(UUID id, UUID eventId) {
        return new EisCommandStore.EisStoredCommand(id, "demo.thing.create", NOW, ACTOR, "accepted", null, null,
                List.of(eventId));
    }

    private static EisCommandStore.EisStoredCommand acceptedCommandWithCreated(UUID id, UUID eventId,
                                                                              EisObjectRef created) {
        return new EisCommandStore.EisStoredCommand(id, "demo.thing.create", NOW, ACTOR, "accepted", null, null,
                List.of(eventId), created);
    }

    private static EisCommandStore.EisStoredCommand rejectedCommand(UUID id) {
        return new EisCommandStore.EisStoredCommand(id, "demo.thing.create", NOW, ACTOR, "rejected",
                "用户名已存在: alice", "IAM-409-001", List.of());
    }

    /**
     * 测试处理器：命令与事件均为演示域。
     */
    private static final class TestHandler implements EisCommandHandler {

        private boolean failing;

        private boolean redact;

        private boolean handled;

        private List<EisCommandRejectionDetail> rejectionDetails = List.of();

        @Override
        public Set<String> commandTypes() {
            return Set.of("demo.thing.create");
        }

        @Override
        public EisCommandHandlerResult handle(EisCommandContext context) {
            handled = true;
            if (failing) {
                throw new EisCommandRejectedException("DEMO-409-001", "演示拒绝", null, rejectionDetails);
            }
            UUID id = context.newId();
            JsonNode payload = MAPPER.createObjectNode().put("thing_id", id.toString());
            return new EisCommandHandlerResult(
                    List.of(new EisEventDraft("demo.thing.created", List.of(SUBJECT), payload)),
                    new EisObjectRef("demo_thing", SUBJECT));
        }

        @Override
        public JsonNode storedPayload(JsonNode payload) {
            if (redact && payload != null && payload.has("secret")) {
                var copy = (tools.jackson.databind.node.ObjectNode) payload.deepCopy();
                copy.remove("secret");
                return copy;
            }
            return payload;
        }
    }

    /**
     * 测试规则：恒拒绝。
     */
    private static final class RejectingRule implements EisRule {

        private final String type;

        private final String code;

        private final String reason;

        private final String reasonCode;

        private RejectingRule(String type, String code, String reason, String reasonCode) {
            this.type = type;
            this.code = code;
            this.reason = reason;
            this.reasonCode = reasonCode;
        }

        @Override
        public Set<String> commandTypes() {
            return Set.of(type);
        }

        @Override
        public void enforce(EisCommandContext context) {
            throw new EisCommandRejectedException(code, reason, reasonCode);
        }
    }
}
