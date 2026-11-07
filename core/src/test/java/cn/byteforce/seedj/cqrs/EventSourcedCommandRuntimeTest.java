package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.audit.AuditErrorCode;
import cn.byteforce.seedj.audit.AuditException;
import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.cqrs.spi.EventStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AbstractAggregateRoot;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.Repository;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.domain.event.EventApplier;
import cn.byteforce.seedj.domain.event.EventType;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** 事件溯源命令走统一信封：幂等、未知字段、脱敏、target_ref、拒绝语义、 scope、事件可见性与事务一致性。 */
class EventSourcedCommandRuntimeTest {

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final UUID OTHER_ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000003");

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final Caller CALLER =
            new Caller(
                    ACTOR.toString(),
                    "alice",
                    "default",
                    null,
                    Set.of("test.write"),
                    null,
                    null,
                    null,
                    null);

    private static final Caller READ_ONLY =
            new Caller(
                    OTHER_ACTOR.toString(),
                    "bob",
                    "default",
                    null,
                    Set.of("test.read"),
                    null,
                    null,
                    null,
                    null);

    private static final JsonMapper SNAKE =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build();

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("test_aggregate", "test_aggregate")
                    .field("name", FieldType.TEXT)
                    .field("org_unit_id", FieldType.TEXT)
                    .writeOnly("id_number", FieldType.TEXT)
                    .build();

    private static final CommandDescriptor DESCRIPTOR =
            CommandDescriptor.builder()
                    .type("test.aggregate.update")
                    .objectType("test_aggregate")
                    .payloadType(TestCommand.class)
                    .permission("test.write")
                    .build();

    @Test
    @DisplayName("advanced 命令接受后回执带 target_ref 与 version")
    void advancedCommandAcceptsWithTargetRefAndVersion() {
        Fixture fixture = new Fixture();

        CommandReceipt receipt = fixture.execute(submission("k-1"));

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(receipt.targetRef()).isEqualTo("agg-1");
        assertThat(receipt.locator()).isNull();
        assertThat(receipt.version()).isEqualTo(1L);
        assertThat(receipt.eventIds()).hasSize(1);
        assertThat(fixture.commands.find(receipt.commandId()).orElseThrow().targetRef())
                .isEqualTo("agg-1");
        assertThat(fixture.events.version("agg-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("同键同载荷重放不解码、不执行、不读聚合")
    void replayDoesNotDecodeOrExecuteAgain() {
        Fixture fixture = new Fixture();
        CommandReceipt first = fixture.execute(submission("k-1"));
        int reads = fixture.events.reads;

        CommandReceipt replay = fixture.execute(submission("k-1"));

        assertThat(replay.commandId()).isEqualTo(first.commandId());
        assertThat(replay.targetRef()).isEqualTo("agg-1");
        assertThat(fixture.events.reads).isEqualTo(reads);
        assertThat(fixture.events.appends).isEqualTo(1);
        assertThat(fixture.events.version("agg-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("同键不同载荷 409，original_result 带 target_ref")
    void sameKeyDifferentPayloadConflicts() {
        Fixture fixture = new Fixture();
        fixture.execute(submission("k-1"));

        assertThatThrownBy(() -> fixture.execute(submission("k-1", "Bob", "D01", null, null)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code())
                                    .isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT);
                            assertThat(error.details().get("original_result").toString())
                                    .contains("target_ref")
                                    .contains("agg-1");
                        });
    }

    @Test
    @DisplayName("未知字段 400，不写命令记录")
    void unknownPayloadFieldIsRejectedBeforePending() {
        Fixture fixture = new Fixture();
        Map<String, Object> payload = new LinkedHashMap<>(basePayload());
        payload.put("telephone", "123");

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        new CommandSubmission(
                                                "test.aggregate.update",
                                                payload,
                                                "k-2",
                                                List.of(),
                                                ACTOR)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThat(fixture.commands.records).isEmpty();
        assertThat(fixture.events.reads).isZero();
    }

    @Test
    @DisplayName("writeOnly 与 redacted 字段不落命令记录，但指纹仍检测载荷变化")
    void writeOnlyAndRedactedFieldsNeverReachTheRecord() {
        Fixture fixture = new Fixture();
        CommandReceipt receipt =
                fixture.execute(submission("k-3", "Alice", "D01", "110101199001011234", "s3cr3t"));

        CommandRecord record = fixture.commands.find(receipt.commandId()).orElseThrow();
        assertThat(record.payload())
                .containsEntry("name", "Alice")
                .doesNotContainKeys("id_number", "secret");

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        submission(
                                                "k-3",
                                                "Alice",
                                                "D01",
                                                "110101199001011234",
                                                "different")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("advanced 命令拒绝非空 targets")
    void nonEmptyTargetsAreRejected() {
        Fixture fixture = new Fixture();
        CommandSubmission submission =
                new CommandSubmission(
                        "test.aggregate.update",
                        basePayload(),
                        "k-4",
                        List.of(UUID.randomUUID()),
                        ACTOR);

        assertThatThrownBy(() -> fixture.execute(submission))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThat(fixture.commands.records).isEmpty();
    }

    @Test
    @DisplayName("target_ref 超过 255 在写 PENDING 前失败")
    void targetRefLengthIsBounded() {
        Fixture fixture = new Fixture();
        Map<String, Object> payload = new LinkedHashMap<>(basePayload());
        payload.put("aggregate_id", "x".repeat(256));

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        new CommandSubmission(
                                                "test.aggregate.update",
                                                payload,
                                                "k-5",
                                                List.of(),
                                                ACTOR)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
        assertThat(fixture.commands.records).isEmpty();
    }

    @Test
    @DisplayName("聚合拒绝写 REJECTED 记录并返回 400")
    void aggregateRejectionWritesRejectedRecord() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.execute(submission("k-6", "reject", "D01", null, null)))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.COMMAND_REJECTED));
        assertThat(fixture.commands.records)
                .singleElement()
                .extracting(CommandRecord::status)
                .isEqualTo(CommandStatus.REJECTED);
        assertThat(fixture.events.version("agg-1")).isZero();
    }

    @Test
    @DisplayName("流不存在且非创建型：404 + REJECTED 记录")
    void missingAggregateWritesRejectedRecord() {
        Fixture fixture = new Fixture(false, CommandAudit.noop(), new FakeCommands());

        assertThatThrownBy(() -> fixture.execute(submission("k-7")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND));
        assertThat(fixture.commands.records)
                .singleElement()
                .extracting(CommandRecord::status)
                .isEqualTo(CommandStatus.REJECTED);
    }

    @Test
    @DisplayName("事件可见性失败：500、流回滚、幂等键可重试")
    void eventVisibilityFailureRollsBackStreamAndReleasesKey() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        submission(
                                                "k-8", "leaky", "D01", "110101199001011234", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id_number");
        assertThat(fixture.events.version("agg-1")).isZero();
        assertThat(fixture.commands.records).isEmpty();

        CommandReceipt retry = fixture.execute(submission("k-8"));
        assertThat(retry.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(fixture.events.version("agg-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("结果状态越界：403、流不变、REJECTED")
    void resultScopeRejectionRollsBackStream() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        submission("k-9", "Alice", "D02", null, null),
                                        ScopePredicate.eq("org_unit_id", "D01")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(fixture.events.version("agg-1")).isZero();
        assertThat(fixture.commands.records)
                .singleElement()
                .extracting(CommandRecord::status)
                .isEqualTo(CommandStatus.REJECTED);
    }

    @Test
    @DisplayName("当前状态越界：403、流不变")
    void currentScopeRejectionKeepsStream() {
        Fixture fixture = new Fixture();
        fixture.execute(submission("k-10", "Alice", "D01", null, null));

        assertThatThrownBy(
                        () ->
                                fixture.execute(
                                        submission("k-11", "Bob", "D01", null, null),
                                        ScopePredicate.eq("org_unit_id", "D02")))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(fixture.events.version("agg-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("并发冲突耗尽：409，PENDING 被释放")
    void exhaustedConflictAbandonsPending() {
        Fixture fixture = new Fixture();
        fixture.events.conflictsRemaining = 10;

        assertThatThrownBy(() -> fixture.execute(submission("k-12")))
                .isInstanceOfSatisfying(
                        CqrsException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo(CqrsErrorCode.CONCURRENCY_CONFLICT));
        assertThat(fixture.commands.records).isEmpty();
        assertThat(fixture.events.version("agg-1")).isZero();
    }

    @Test
    @DisplayName("complete 失败：流回滚、记录释放")
    void completeFailureRollsBackStream() {
        FakeCommands failing =
                new FakeCommands() {
                    @Override
                    public void complete(CommandRecord record) {
                        throw new IllegalStateException("store down");
                    }
                };
        Fixture fixture = new Fixture(true, CommandAudit.noop(), failing);

        assertThatThrownBy(() -> fixture.execute(submission("k-13")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store down");
        assertThat(fixture.events.version("agg-1")).isZero();
        assertThat(fixture.commands.records).isEmpty();
    }

    @Test
    @DisplayName("audit 失败：流与记录一起回滚")
    void auditFailureRollsBackStreamAndRecord() {
        CommandAudit failing =
                new CommandAudit() {
                    @Override
                    public void accepted(Command<?> command, String resourceId) {
                        throw new AuditException(AuditErrorCode.APPEND_FAILED, "audit down");
                    }
                };
        Fixture fixture = new Fixture(true, failing, new FakeCommands());

        assertThatThrownBy(() -> fixture.execute(submission("k-14")))
                .isInstanceOf(AuditException.class);
        assertThat(fixture.events.version("agg-1")).isZero();
        assertThat(fixture.commands.records).isEmpty();
    }

    @Test
    @DisplayName("权限不足：403，聚合不加载、流不变")
    void missingPermissionDoesNotLoadAggregate() {
        Fixture fixture = new Fixture();
        CommandCatalog catalog =
                new CommandCatalog(
                        List.of(fixture.registration),
                        CommandPolicy.permissions(),
                        fixture.runtime,
                        (objectType, caller) -> ScopePredicate.all());

        assertThatThrownBy(() -> catalog.execute(submission("k-15"), READ_ONLY))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
        assertThat(fixture.events.reads).isZero();
        assertThat(fixture.events.version("agg-1")).isZero();
    }

    // ---------------------------------------------------------------- fixtures

    private static Map<String, Object> basePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregate_id", "agg-1");
        payload.put("name", "Alice");
        payload.put("org_unit_id", "D01");
        return payload;
    }

    private static CommandSubmission submission(String key) {
        return submission(key, "Alice", "D01", null, null);
    }

    private static CommandSubmission submission(
            String key, String name, String orgUnitId, String idNumber, String secret) {
        Map<String, Object> payload = basePayload();
        payload.put("name", name);
        payload.put("org_unit_id", orgUnitId);
        if (idNumber != null) {
            payload.put("id_number", idNumber);
        }
        if (secret != null) {
            payload.put("secret", secret);
        }
        return new CommandSubmission("test.aggregate.update", payload, key, List.of(), ACTOR);
    }

    static final class Fixture {

        final FakeEventStore events = new FakeEventStore();

        final FakeCommands commands;

        final SnapshotTransactionRunner transactionRunner = new SnapshotTransactionRunner(events);

        final TestAgent agent;

        final CommandRegistration registration;

        final CommandRuntime runtime;

        Fixture() {
            this(true, CommandAudit.noop(), new FakeCommands());
        }

        Fixture(boolean creates, CommandAudit audit, FakeCommands commands) {
            this.commands = commands;
            TestClock clock = new TestClock(NOW);
            AtomicLong ids = new AtomicLong();
            this.agent =
                    new TestAgent(
                            new TestRepository(events),
                            new NoopPublisher(),
                            IdCodecs.strings(),
                            new CommandAgent.Options<>(
                                    transactionRunner,
                                    LockProvider.disabled(),
                                    Duration.ofSeconds(30),
                                    3,
                                    List.of(),
                                    audit),
                            creates);
            this.registration =
                    CommandRegistration.eventSourced(
                            DESCRIPTOR,
                            agent,
                            SCHEMA,
                            Set.of("aggregate_id", "secret"),
                            Set.of("secret"),
                            List.of(
                                    "test.aggregate.created",
                                    "test.aggregate.renamed",
                                    "test.aggregate.leaky"));
            this.runtime =
                    new CommandRuntime(
                            new CommandRuntime.Options(
                                    transactionRunner,
                                    LockProvider.disabled(),
                                    Duration.ofSeconds(30),
                                    clock,
                                    () -> new UUID(0, ids.incrementAndGet()),
                                    commands,
                                    new CommandRuntimeTest.FakeObjects(),
                                    OutboxWriter.discard(),
                                    TemporalCommandAudit.noop(),
                                    SNAKE));
        }

        CommandReceipt execute(CommandSubmission submission) {
            return execute(submission, ScopePredicate.all());
        }

        CommandReceipt execute(CommandSubmission submission, ScopePredicate scope) {
            return runtime.execute(submission, registration, CALLER, scope);
        }
    }

    static class FakeCommands extends CommandRuntimeTest.FakeCommands {}

    static final class FakeEventStore implements EventStore<String> {

        final Map<String, List<DomainEvent>> streams = new LinkedHashMap<>();

        int reads;

        int appends;

        int conflictsRemaining;

        @Override
        public EventStream<String> read(StreamId<String> streamId) {
            reads++;
            List<DomainEvent> history = streams.get(streamId.id());
            if (history == null || history.isEmpty()) {
                return EventStream.empty(streamId);
            }
            return new EventStream<>(streamId, history.size(), history);
        }

        @Override
        public long append(
                StreamId<String> streamId,
                long expectedVersion,
                List<? extends DomainEvent> events) {
            appends++;
            if (conflictsRemaining > 0) {
                conflictsRemaining--;
                throw new CqrsException(CqrsErrorCode.CONCURRENCY_CONFLICT, "simulated");
            }
            if (events.isEmpty()) {
                return expectedVersion;
            }
            List<DomainEvent> current = streams.getOrDefault(streamId.id(), List.of());
            if (current.size() != expectedVersion) {
                throw new CqrsException(
                        CqrsErrorCode.CONCURRENCY_CONFLICT,
                        "expected " + expectedVersion + " but was " + current.size());
            }
            List<DomainEvent> next = new ArrayList<>(current);
            next.addAll(events);
            streams.put(streamId.id(), List.copyOf(next));
            return next.size();
        }

        long version(String id) {
            return streams.getOrDefault(id, List.of()).size();
        }

        Map<String, List<DomainEvent>> snapshot() {
            Map<String, List<DomainEvent>> copy = new LinkedHashMap<>();
            streams.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return copy;
        }

        void restore(Map<String, List<DomainEvent>> snapshot) {
            streams.clear();
            snapshot.forEach((key, value) -> streams.put(key, List.copyOf(value)));
        }
    }

    static final class SnapshotTransactionRunner implements TransactionRunner {

        private final FakeEventStore store;

        SnapshotTransactionRunner(FakeEventStore store) {
            this.store = store;
        }

        @Override
        public <R> R inTransaction(Supplier<R> work) {
            Map<String, List<DomainEvent>> snapshot = store.snapshot();
            try {
                return work.get();
            } catch (RuntimeException error) {
                store.restore(snapshot);
                throw error;
            }
        }
    }

    static final class TestRepository extends EventSourcedRepository<TestAggregate, String> {

        TestRepository(EventStore<String> store) {
            super(store);
        }

        @Override
        protected Class<TestAggregate> aggregateType() {
            return TestAggregate.class;
        }

        @Override
        protected TestAggregate empty(String id) {
            return TestAggregate.blank(id);
        }

        @Override
        protected String streamType() {
            return TestAggregate.STREAM_TYPE;
        }
    }

    sealed interface TestEvent extends DomainEvent permits Created, Renamed, Leaky {}

    @EventType("test.aggregate.created")
    record Created(String aggregateId, String name, String orgUnitId) implements TestEvent {}

    @EventType("test.aggregate.renamed")
    record Renamed(String aggregateId, String name) implements TestEvent {}

    @EventType("test.aggregate.leaky")
    record Leaky(String aggregateId, String name, String idNumber) implements TestEvent {}

    static final class TestAggregate extends AbstractAggregateRoot<String>
            implements EventApplier<TestEvent> {

        static final String STREAM_TYPE = "test.aggregate";

        private String name;

        private String orgUnitId;

        private TestAggregate(String id) {
            super(id);
        }

        static TestAggregate blank(String id) {
            return new TestAggregate(id);
        }

        static TestAggregate create(String id, String name, String orgUnitId) {
            TestAggregate aggregate = new TestAggregate(id);
            aggregate.raise(
                    new Created(
                            id,
                            Objects.requireNonNull(name, "name"),
                            Objects.requireNonNull(orgUnitId, "orgUnitId")));
            return aggregate;
        }

        void rename(String nextName) {
            raise(new Renamed(getId(), Objects.requireNonNull(nextName, "name")));
        }

        void leak(String nextName, String idNumber) {
            raise(new Leaky(getId(), nextName, idNumber));
        }

        public String name() {
            return name;
        }

        public String orgUnitId() {
            return orgUnitId;
        }

        protected void apply(TestEvent event) {
            switch (event) {
                case Created created -> {
                    this.name = created.name();
                    this.orgUnitId = created.orgUnitId();
                }
                case Renamed renamed -> this.name = renamed.name();
                case Leaky leaky -> this.name = leaky.name();
            }
        }
    }

    record TestCommand(
            String aggregateId, String name, String orgUnitId, String idNumber, String secret)
            implements Command<String> {

        TestCommand {
            Objects.requireNonNull(aggregateId, "aggregateId");
        }
    }

    static final class TestAgent extends CommandAgent<TestCommand, TestAggregate, String> {

        private final boolean creates;

        TestAgent(
                Repository<TestAggregate, String> repository,
                EventPublisher publisher,
                IdCodec<String> codec,
                Options<String> options,
                boolean creates) {
            super(repository, publisher, codec, options);
            this.creates = creates;
        }

        @Override
        protected String aggregateType() {
            return TestAggregate.STREAM_TYPE;
        }

        @Override
        protected boolean createsOnEmpty() {
            return creates;
        }

        @Override
        protected TestAggregate create(String id, TestCommand command) {
            if ("reject".equals(command.name())) {
                throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED, "rejected by aggregate");
            }
            TestAggregate aggregate = TestAggregate.create(id, command.name(), command.orgUnitId());
            if ("leaky".equals(command.name())) {
                aggregate.leak(command.name(), command.idNumber());
            }
            return aggregate;
        }

        @Override
        protected void handle(TestAggregate aggregate, TestCommand command) {
            if ("reject".equals(command.name())) {
                throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED, "rejected by aggregate");
            }
            if ("leaky".equals(command.name())) {
                aggregate.leak(command.name(), command.idNumber());
                return;
            }
            aggregate.rename(command.name());
        }
    }

    static final class NoopPublisher implements EventPublisher {

        @Override
        public void publish(DomainEvent event) {}

        @Override
        public void publish(EventMessage message) {}
    }
}
