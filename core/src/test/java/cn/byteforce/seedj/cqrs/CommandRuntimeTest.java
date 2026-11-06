package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRuntimeTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            null, Set.of("probe.write"), null, null, null, null);

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("probe", "probe_object")
            .field("employee_no", FieldType.TEXT)
            .field("org_unit_id", FieldType.TEXT)
            .build();

    private static final JsonMapper SNAKE = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private static CommandDescriptor descriptor() {
        return CommandDescriptor.builder()
                .type("probe.update")
                .objectType("probe")
                .payloadType(Payload.class)
                .permission("probe.write")
                .build();
    }

    private static CommandSubmission submission() {
        return new CommandSubmission("probe.update", Map.of("employee_no", "E-1"), "key-1",
                List.of(), ACTOR);
    }

    private static CommandRuntime runtime(CommandStore commands, TemporalStore objects,
                                          JsonMapper mapper) {
        AtomicLong sequence = new AtomicLong();
        return new CommandRuntime(new CommandRuntime.Options(
                TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30),
                new TestClock(NOW),
                () -> new UUID(0, sequence.incrementAndGet()), commands, objects,
                OutboxWriter.discard(), TemporalCommandAudit.noop(), mapper));
    }

    private static CommandRegistration registration(CommandHandler<Payload> handler) {
        return new CommandRegistration(descriptor(), handler, SCHEMA);
    }

    @Test
    void replayDoesNotCallTargetId() {
        CountingHandler handler = new CountingHandler("D01", false);
        FakeCommands commands = new FakeCommands();
        CommandRuntime runtime = runtime(commands, new FakeObjects(), SNAKE);

        runtime.execute(submission(), registration(handler), CALLER, ScopePredicate.all());
        CommandReceipt replay = runtime.execute(submission(), registration(handler), CALLER,
                ScopePredicate.all());

        assertThat(handler.targetIdCalls).isEqualTo(1);
        assertThat(handler.decideCalls).isEqualTo(1);
        assertThat(replay.status()).isEqualTo(CommandStatus.ACCEPTED);
    }

    @Test
    void sameKeyDifferentIssuerIsNotReplay() {
        CountingHandler handler = new CountingHandler("D01", false);
        CommandRuntime runtime = runtime(new FakeCommands(), new FakeObjects(), SNAKE);
        UUID otherIssuer = UUID.randomUUID();
        CommandSubmission other = new CommandSubmission("probe.update",
                Map.of("employee_no", "E-1"), "key-1", List.of(), otherIssuer);

        runtime.execute(submission(), registration(handler), CALLER, ScopePredicate.all());
        runtime.execute(other, registration(handler), CALLER, ScopePredicate.all());

        assertThat(handler.targetIdCalls).isEqualTo(2);
        assertThat(handler.decideCalls).isEqualTo(2);
    }

    @Test
    void writeScopeRejectsTargetBeforeDecide() {
        CountingHandler handler = new CountingHandler("D02", false);
        FakeCommands commands = new FakeCommands();
        FakeObjects objects = new FakeObjects();
        objects.seed(row("D02"));
        CommandRuntime runtime = runtime(commands, objects, SNAKE);

        assertThatThrownBy(() -> runtime.execute(submission(), registration(handler), CALLER,
                ScopePredicate.eq("org_unit_id", "D01")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(handler.decideCalls).isZero();
        assertThat(commands.records).hasSize(1);
        assertThat(commands.records.get(0).status()).isEqualTo(CommandStatus.REJECTED);
    }

    @Test
    void writeScopeRejectsCreatedFieldsOutsideScope() {
        CountingHandler handler = new CountingHandler("D02", false);
        FakeObjects objects = new FakeObjects();
        CommandRuntime runtime = runtime(new FakeCommands(), objects, SNAKE);

        assertThatThrownBy(() -> runtime.execute(submission(), registration(handler), CALLER,
                ScopePredicate.eq("org_unit_id", "D01")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(handler.decideCalls).isEqualTo(1);
        assertThat(objects.appends).isZero();
    }

    @Test
    void duplicateEventKeysFail() {
        CountingHandler handler = new CountingHandler("D01", true);
        CommandRuntime runtime = runtime(new FakeCommands(), new FakeObjects(), SNAKE);

        assertThatThrownBy(() -> runtime.execute(submission(), registration(handler), CALLER,
                ScopePredicate.all()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate event");
    }

    @Test
    void jsonMapperIsInjected() {
        CountingHandler handler = new CountingHandler("D01", false);
        CommandRuntime runtime = runtime(new FakeCommands(), new FakeObjects(), SNAKE);

        runtime.execute(submission(), registration(handler), CALLER, ScopePredicate.all());

        assertThat(handler.seen.employeeNo()).isEqualTo("E-1");
    }

    @Test
    void validationFailureDoesNotCreatePendingRecord() {
        CountingHandler handler = new CountingHandler("D01", false) {
            @Override
            public AppendRequest decide(Payload command, CommandContext ctx) {
                throw new AssertionError("decide must not run");
            }
        };
        FakeCommands commands = new FakeCommands();
        CommandDescriptor descriptor = CommandDescriptor.builder()
                .type("probe.update")
                .objectType("probe")
                .payloadType(Payload.class)
                .permission("probe.write")
                .validator(command -> {
                    throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "bad");
                })
                .build();
        CommandRuntime runtime = runtime(commands, new FakeObjects(), SNAKE);

        assertThatThrownBy(() -> runtime.execute(submission(),
                new CommandRegistration(descriptor, handler, SCHEMA), CALLER,
                ScopePredicate.all()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThat(commands.records).isEmpty();
    }

    @Test
    void handlerRejectionWritesRejectedRecord() {
        CountingHandler handler = new CountingHandler("D01", false) {
            @Override
            public AppendRequest decide(Payload command, CommandContext ctx) {
                throw new ContractException(ContractErrorCode.COMMAND_REJECTED, "nope");
            }
        };
        FakeCommands commands = new FakeCommands();
        CommandRuntime runtime = runtime(commands, new FakeObjects(), SNAKE);

        assertThatThrownBy(() -> runtime.execute(submission(), registration(handler), CALLER,
                ScopePredicate.all()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED));
        assertThat(commands.records).hasSize(1);
        assertThat(commands.records.get(0).status()).isEqualTo(CommandStatus.REJECTED);
    }

    private static TemporalRow row(String unit) {
        return new TemporalRow("probe", new ObjectVersion(ID, 1, NOW, null, NOW, null,
                ACTOR, NOW, null, null), Map.of("org_unit_id", unit));
    }

    static class CountingHandler implements CommandHandler<Payload> {

        final String orgUnit;

        final boolean duplicateEvents;

        int targetIdCalls;

        int decideCalls;

        Payload seen;

        CountingHandler(String orgUnit, boolean duplicateEvents) {
            this.orgUnit = orgUnit;
            this.duplicateEvents = duplicateEvents;
        }

        @Override
        public UUID targetId(Payload command) {
            targetIdCalls++;
            return ID;
        }

        @Override
        public AppendRequest decide(Payload command, CommandContext ctx) {
            decideCalls++;
            seen = command;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("employee_no", command.employeeNo());
            fields.put("org_unit_id", orgUnit);
            return AppendRequest.create("probe", ctx.targetId(), ctx.now(), null, ctx.actor(),
                    fields);
        }

        @Override
        public List<CommandEvent> events(Payload command, AppendResult result,
                                         CommandContext ctx) {
            CommandEvent event = ctx.event("probe.updated", Map.of());
            return duplicateEvents ? List.of(event, event) : List.of(event);
        }
    }

    record Payload(String employeeNo) {
    }

    static final class FakeCommands implements CommandStore {

        final List<CommandRecord> records = new ArrayList<>();

        @Override
        public Optional<CommandRecord> find(UUID id) {
            return records.stream().filter(record -> record.id().equals(id)).findFirst();
        }

        @Override
        public Optional<CommandRecord> findByIdempotencyKey(UUID issuer, String idempotencyKey) {
            return records.stream().filter(record ->
                    record.issuer().equals(issuer)
                            && record.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public boolean insert(CommandRecord record) {
            if (find(record.id()).isPresent()
                    || findByIdempotencyKey(record.issuer(), record.idempotencyKey()).isPresent()) {
                return false;
            }
            records.add(record);
            return true;
        }

        @Override
        public void complete(CommandRecord record) {
            for (int i = 0; i < records.size(); i++) {
                if (records.get(i).id().equals(record.id())) {
                    records.set(i, record);
                    return;
                }
            }
            throw new IllegalStateException("missing record");
        }

        @Override
        public void abandon(UUID id) {
            records.removeIf(record -> record.id().equals(id));
        }
    }

    static final class FakeObjects implements TemporalStore {

        final Map<UUID, TemporalRow> current = new LinkedHashMap<>();

        int appends;

        void seed(TemporalRow row) {
            current.put(row.version().id(), row);
        }

        @Override
        public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                                          ScopePredicate scope) {
            return Optional.ofNullable(current.get(id))
                    .filter(row -> ScopePredicates.matches(row, scope, SCHEMA));
        }

        @Override
        public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
            return Page.empty(search.page());
        }

        @Override
        public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
            return List.of();
        }

        @Override
        public AppendResult append(AppendRequest request) {
            appends++;
            return new AppendResult(new TemporalRow("probe",
                    new ObjectVersion(request.id(), 1, request.validFrom(), null, NOW, null,
                            request.actor(), NOW, null, null), request.fields()),
                    Optional.empty());
        }
    }
}
