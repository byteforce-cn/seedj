package cn.byteforce.seedj.audit.wiring;

import cn.byteforce.seedj.audit.AuditAppender;
import cn.byteforce.seedj.audit.AuditChain;
import cn.byteforce.seedj.audit.AuditEntry;
import cn.byteforce.seedj.audit.AuditEvent;
import cn.byteforce.seedj.audit.AuditPayloadDigest;
import cn.byteforce.seedj.audit.AuditVerifier;
import cn.byteforce.seedj.audit.memory.InMemoryAuditStore;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.CommandReceipt;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.CommandStatus;
import cn.byteforce.seedj.cqrs.CommandSubmission;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 临时命令审计：写链位置、payload、摘要与拒绝路径。
 */
class TemporalCommandAuditTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private final Clock clock = now::get;

    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);

    private final InMemoryCommandStore commands = new InMemoryCommandStore();

    private final InMemoryAuditStore auditStore = new InMemoryAuditStore();

    private final AtomicLong auditIds = new AtomicLong();

    private final AuditChain<String> chain = new AuditChain<>(auditStore, clock, head -> {
    }, () -> "audit-" + auditIds.incrementAndGet());

    private final TemporalCommandAuditAdapter audit =
            new TemporalCommandAuditAdapter(chain, () -> "tester");

    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, null, audit));

    private final ProbeAgent agent = new ProbeAgent(runtime);

    @BeforeEach
    void reset() {
        now.set(T0);
        commands.clear();
        objects.clear();
    }

    @Test
    @DisplayName("受理命令写一条审计：动作、资源、命令 ID 与事件 ID")
    void acceptedCommandWritesOneAudit() {
        CommandReceipt receipt = agent.execute(submission("k-1", "Alice", false));

        assertThat(auditStore.loadAll()).hasSize(1);
        AuditEntry<String> entry = auditStore.loadAll().get(0);
        assertThat(entry.getAction()).isEqualTo("probe.update");
        assertThat(entry.getActor()).isEqualTo("tester");
        assertThat(entry.getResourceType()).isEqualTo("probe");
        assertThat(entry.getResourceId()).isEqualTo(ID.toString());
        assertThat(entry.getPayload().get("command_id").asString())
                .isEqualTo(receipt.commandId().toString());
        assertThat(entry.getPayload().get("event_ids")).hasSize(1);
        assertThat(entry.getPayload().get("event_ids").get(0).asString())
                .isEqualTo(receipt.eventIds().get(0).toString());
    }

    @Test
    @DisplayName("摘要与写入事件一致，事件导出可复算")
    void digestVerifiesAgainstWrittenEvents() {
        CommandReceipt receipt = agent.execute(submission("k-2", "Alice", false));
        UUID eventId = receipt.eventIds().get(0);
        AuditEvent event = new AuditEvent(eventId, "probe.updated",
                JSON.valueToTree(Map.of("name", "Alice")));

        AuditEntry<String> entry = auditStore.loadAll().get(0);
        assertThat(entry.getPayloadDigest()).isEqualTo(AuditPayloadDigest.compute(List.of(event)));
        assertThat(AuditVerifier.verify(auditStore.loadAll(), Map.of(eventId, event))).isEmpty();
    }

    @Test
    @DisplayName("业务拒绝不进入哈希链")
    void rejectedCommandDoesNotWriteHashChain() {
        assertThatThrownBy(() -> agent.execute(submission("k-3", "Alice", true)))
                .isInstanceOf(ContractException.class);

        assertThat(auditStore.loadAll()).isEmpty();
        assertThat(commands.findByIdempotencyKey("k-3")).get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(CommandStatus.REJECTED));
    }

    @Test
    @DisplayName("审计失败向上抛出，待处理命令被释放")
    void auditFailureIsNotSwallowed() {
        AuditAppender<String> failing = request -> {
            throw new IllegalStateException("audit store down");
        };
        CommandRuntime failingRuntime = new CommandRuntime(new CommandRuntime.Options(
                TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30),
                clock, new Uuid7IdGenerator(clock), commands, objects, null,
                new TemporalCommandAuditAdapter(failing, () -> "tester")));
        ProbeAgent failingAgent = new ProbeAgent(failingRuntime);

        assertThatThrownBy(() -> failingAgent.execute(submission("k-4", "Alice", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit store down");
        assertThat(commands.findByIdempotencyKey("k-4")).isEmpty();
    }

    private static CommandSubmission submission(String key, String name, boolean reject) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", ID.toString());
        payload.put("name", name);
        payload.put("reject", reject);
        return new CommandSubmission("probe.update", payload, key, List.of(ID), ACTOR);
    }

    static final class ProbeAgent extends TemporalCommandAgent<ProbeCommand> {

        ProbeAgent(CommandRuntime runtime) {
            super(runtime, "probe.update", "probe", ProbeCommand.class);
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected void validate(ProbeCommand command) {
            if (command.reject()) {
                throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                        "rejected by probe");
            }
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
            Map<String, Object> fields = Map.of("name", command.name());
            if (current.isPresent()) {
                return AppendRequest.correction("probe", command.target(), now, null, actor,
                        fields);
            }
            return AppendRequest.create("probe", command.target(), now, null, actor, fields);
        }

        @Override
        protected List<CommandEvent> events(ProbeCommand command, AppendResult result,
                                           Instant now, UUID actor) {
            return List.of(new CommandEvent(newId(), "probe.updated", now, now, actor,
                    List.of(command.target()), Map.of("name", command.name()),
                    "probe.update:probe:1", 1));
        }
    }

    record ProbeCommand(UUID target, String name, boolean reject) {
    }
}
