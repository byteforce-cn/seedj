package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令记录脱敏：只影响落库载荷，不改变幂等指纹。
 */
class CommandRuntimeRedactionTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final AtomicReference<Instant> now = new AtomicReference<>(
            Instant.parse("2026-10-08T09:00:00Z"));
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, null, TemporalCommandAudit.noop(),
            payload -> {
                Map<String, Object> redacted = new LinkedHashMap<>(payload);
                redacted.remove("secret");
                return redacted;
            }));
    private final RecordingAgent agent = new RecordingAgent(runtime);

    @Test
    @DisplayName("命令记录不含原始字段，重放仍命中幂等")
    void commandRecordIsRedactedAndReplayWorks() {
        CommandReceipt first = agent.execute(submission("k-1", "raw-1"));

        assertThat(commands.findByIdempotencyKey("k-1")).get()
                .satisfies(record -> assertThat(record.payload()).doesNotContainKey("secret"));
        CommandReceipt replay = agent.execute(submission("k-1", "raw-1"));
        assertThat(replay.commandId()).isEqualTo(first.commandId());
    }

    @Test
    @DisplayName("同键不同原始字段仍触发幂等冲突")
    void fingerprintStillUsesRawPayload() {
        agent.execute(submission("k-2", "raw-1"));

        assertThatThrownBy(() -> agent.execute(submission("k-2", "raw-2")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT));
    }

    private static CommandSubmission submission(String key, String secret) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", ID.toString());
        payload.put("secret", secret);
        payload.put("name", "Alice");
        return new CommandSubmission("probe.update", payload, key, List.of(ID), ACTOR);
    }

    static final class RecordingAgent extends TemporalCommandAgent<Probe> {

        RecordingAgent(CommandRuntime runtime) {
            super(runtime, "probe.update", "probe", Probe.class);
        }

        @Override
        protected UUID targetId(Probe command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(Probe command, java.util.Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
            return AppendRequest.create("probe", command.target(), now, null, actor,
                    Map.of("name", command.name()));
        }
    }

    record Probe(UUID target, String secret, String name) {
    }
}
