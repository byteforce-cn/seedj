package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockLease;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令运行时的顺序、幂等、拒绝与事件信封。
 */
class TemporalCommandAgentTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TestClock clock = new TestClock(T0);

    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);

    private final InMemoryCommandStore commands = new InMemoryCommandStore();

    private final RecordingOutbox outbox = new RecordingOutbox();

    private final RecordingLock lock = new RecordingLock();

    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), lock, Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, outbox));

    private final ProbeAgent agent = new ProbeAgent(runtime);

    @BeforeEach
    void resetState() {
        clock.set(T0);
        commands.clear();
        objects.clear();
        outbox.records.clear();
        lock.keys.clear();
    }

    @Test
    @DisplayName("接受命令：追加对象、完成命令、写出事件")
    void acceptedAppendsObjectAndCompletesCommand() {
        CommandReceipt receipt = agent.execute(submission("k-1", "Alice", false));

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(receipt.locator()).isEqualTo(ID);
        assertThat(receipt.version()).isEqualTo(1L);
        assertThat(receipt.eventIds()).hasSize(1);
        assertThat(objects.current("probe", ID)).get()
                .satisfies(row -> assertThat(row.fields()).containsEntry("name", "Alice"));
        assertThat(commands.find(receipt.commandId())).get()
                .satisfies(record -> assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED));
        assertThat(outbox.records).hasSize(1);
    }

    @Test
    @DisplayName("同键同载荷重放：不产生第二个版本、第二条命令或第二个事件")
    void sameKeySamePayloadReplaysWithoutSecondAppend() {
        CommandReceipt first = agent.execute(submission("k-2", "Alice", false));
        CommandReceipt second = agent.execute(submission("k-2", "Alice", false));

        assertThat(second.commandId()).isEqualTo(first.commandId());
        assertThat(second.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(objects.history("probe", ID)).hasSize(1);
        assertThat(outbox.records).hasSize(1);
    }

    @Test
    @DisplayName("同键不同载荷：409 幂等冲突并带原命令")
    void sameKeyDifferentPayloadConflicts() {
        CommandReceipt first = agent.execute(submission("k-3", "Alice", false));

        assertThatThrownBy(() -> agent.execute(submission("k-3", "Bob", false)))
                .isInstanceOfSatisfying(ContractException.class, error -> {
                    assertThat(error.code()).isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT);
                    assertThat(error.details())
                            .containsEntry("original_command_id", first.commandId().toString())
                            .containsEntry("idempotency_key", "k-3");
                });
        assertThat(objects.history("probe", ID)).hasSize(1);
    }

    @Test
    @DisplayName("拒绝命令：对象不落库，命令记录是 rejected；重放仍返回拒绝")
    void rejectedCommandPersistsRejection() {
        assertThatThrownBy(() -> agent.execute(submission("k-4", "Alice", true)))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION));

        assertThat(objects.history("probe", ID)).isEmpty();
        assertThat(commands.findByIdempotencyKey("k-4")).get().satisfies(record -> {
            assertThat(record.status()).isEqualTo(CommandStatus.REJECTED);
            assertThat(record.rejectionCode()).isEqualTo("EIS-400-003");
        });

        CommandReceipt replay = agent.execute(submission("k-4", "Alice", true));
        assertThat(replay.status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(replay.code()).isEqualTo("EIS-400-003");
        assertThat(objects.history("probe", ID)).isEmpty();
    }

    @Test
    @DisplayName("命中 pending：不再次执行")
    void pendingReplayDoesNotExecuteAgain() {
        CommandSubmission submission = submission("k-5", "Alice", false);
        CommandRecord pending = CommandRecord.pending(UUID.randomUUID(), submission.type(),
                T0, ACTOR, submission.payload(), submission.idempotencyKey(),
                IdempotencyFingerprint.of(submission), T0);
        commands.insert(pending);

        CommandReceipt receipt = agent.execute(submission);

        assertThat(receipt.commandId()).isEqualTo(pending.id());
        assertThat(receipt.status()).isEqualTo(CommandStatus.PENDING);
        assertThat(objects.history("probe", ID)).isEmpty();
        assertThat(outbox.records).isEmpty();
    }

    @Test
    @DisplayName("锁键是对象类型 + 目标标识")
    void lockKeyIsObjectTypeAndTarget() {
        agent.execute(submission("k-6", "Alice", false));

        assertThat(lock.keys).containsExactly("probe:" + ID);
    }

    @Test
    @DisplayName("事件信封是契约线格式")
    void eventEnvelopeUsesContractWireFormat() {
        CommandReceipt receipt = agent.execute(submission("k-7", "Alice", false));

        OutboxRecord record = outbox.records.get(0);
        assertThat(record.messageId()).isEqualTo(receipt.eventIds().get(0).toString());
        assertThat(record.destination()).isEqualTo("probe.updated");
        assertThat(record.contentType()).isEqualTo("eis://core/event/v1");

        Map<String, Object> payload = JSON.readValue(record.payload(),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(payload).containsKeys("id", "type", "occurred_at", "recorded_at",
                "actor", "subjects", "payload", "idempotency_key", "version");
        assertThat(payload.get("type")).isEqualTo("probe.updated");
    }


    @Test
    @DisplayName("技术故障释放幂等键，同键可安全重试")
    void technicalFailureReleasesKeyForRetry() {
        agent.failTechnically = true;

        assertThatThrownBy(() -> agent.execute(submission("k-tech", "Alice", false)))
                .isInstanceOf(InfrastructureException.class);
        assertThat(commands.findByIdempotencyKey("k-tech")).isEmpty();

        agent.failTechnically = false;
        CommandReceipt receipt = agent.execute(submission("k-tech", "Alice", false));

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(objects.history("probe", ID)).hasSize(1);
    }

    @Test
    @DisplayName("并发同键只执行一次")
    void concurrentSameKeyExecutesOnce() throws Exception {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<CommandReceipt> task = () -> {
                start.await();
                return agent.execute(submission("k-concurrent", "Alice", false));
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();

            CommandReceipt firstReceipt = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            CommandReceipt secondReceipt = second.get(5, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(firstReceipt.commandId()).isEqualTo(secondReceipt.commandId());
            assertThat(objects.history("probe", ID)).hasSize(1);
            assertThat(outbox.records).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static CommandSubmission submission(String key, String name, boolean reject) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", ID.toString());
        payload.put("name", name);
        payload.put("reject", reject);
        return new CommandSubmission("probe.update", payload, key, List.of(ID), ACTOR);
    }

    /**
     * 测试用命令代理：创建/更正一个 probe 对象，并发出一个事件。
     */
    static final class ProbeAgent extends TemporalCommandAgent<ProbeCommand> {

        private boolean failTechnically;

        ProbeAgent(CommandRuntime runtime) {
            super(runtime, "probe.update", "probe", ProbeCommand.class);
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
            if (failTechnically) {
                throw new InfrastructureException(InfrastructureErrorCode.NOT_READY,
                        "temporal store down");
            }
            if (command.reject()) {
                throw new ContractException(ContractErrorCode.TEMPORAL_VIOLATION,
                        "rejected by probe");
            }
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
            UUID eventId = newId();
            return List.of(new CommandEvent(eventId, "probe.updated", now,
                    result.appended().version().recordedAt(), actor,
                    List.of(command.target()), Map.of("name", command.name()),
                    eventId + ":probe:0", 1));
        }
    }

    /**
     * 测试命令载荷。
     */
    record ProbeCommand(UUID target, String name, boolean reject) {
    }

    /**
     * 记录 outbox 投递。
     */
    static final class RecordingOutbox implements OutboxWriter {

        private final List<OutboxRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void append(OutboxRecord record) {
            records.add(record);
        }
    }

    /**
     * 记录锁键并立即授权。
     */
    static final class RecordingLock implements LockProvider {

        private final List<String> keys = new ArrayList<>();

        @Override
        public Optional<LockLease> tryAcquire(String key, Duration ttl) {
            keys.add(key);
            return Optional.of(LockLease.of(key, () -> {
            }));
        }
    }
}
