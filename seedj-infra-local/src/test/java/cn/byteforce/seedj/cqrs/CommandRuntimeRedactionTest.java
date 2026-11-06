package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.cqrs.memory.InMemoryCommandStore;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.memory.InMemoryTemporalStore;
import cn.byteforce.seedj.id.Uuid7IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令记录脱敏：脱敏声明在 descriptor 上，只影响落库载荷，不改变幂等指纹。
 */
class CommandRuntimeRedactionTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final AtomicReference<Instant> now = new AtomicReference<>(
            Instant.parse("2026-10-08T09:00:00Z"));
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));

    private final RecordingAgent redacting = new RecordingAgent(runtime, "probe.update",
            payload -> {
                Map<String, Object> redacted = new LinkedHashMap<>(payload);
                redacted.remove("secret");
                return redacted;
            });
    private final RecordingAgent plain = new RecordingAgent(runtime, "probe.plain",
            CommandPayloadRedactor.noop());

    @Test
    @DisplayName("命令记录不含原始字段，重放仍命中幂等")
    void commandRecordIsRedactedAndReplayWorks() {
        CommandReceipt first = redacting.execute(submission("probe.update", "k-1", "raw-1", ID));

        assertThat(commands.findByIdempotencyKey("k-1")).get()
                .satisfies(record -> assertThat(record.payload()).doesNotContainKey("secret"));
        CommandReceipt replay = redacting.execute(submission("probe.update", "k-1", "raw-1", ID));
        assertThat(replay.commandId()).isEqualTo(first.commandId());
    }

    @Test
    @DisplayName("同键不同原始字段仍触发幂等冲突")
    void fingerprintStillUsesRawPayload() {
        redacting.execute(submission("probe.update", "k-2", "raw-1", ID));

        assertThatThrownBy(() -> redacting.execute(submission("probe.update", "k-2", "raw-2", ID)))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("两个 agent 的 redactor 互不影响")
    void redactorsArePerAgent() {
        redacting.execute(submission("probe.update", "k-3", "raw-3", ID));
        plain.execute(submission("probe.plain", "k-4", "raw-4",
                                UUID.fromString("018f2e3a-1234-7000-8000-000000000002")));

        assertThat(commands.findByIdempotencyKey("k-3")).get()
                .satisfies(record -> assertThat(record.payload()).doesNotContainKey("secret"));
        assertThat(commands.findByIdempotencyKey("k-4")).get()
                .satisfies(record -> assertThat(record.payload()).containsEntry("secret", "raw-4"));
    }

    private static CommandSubmission submission(String type, String key, String secret, UUID target) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.toString());
        payload.put("secret", secret);
        payload.put("name", "Alice");
        return new CommandSubmission(type, payload, key, List.of(target), ACTOR);
    }

    static final class RecordingAgent extends TemporalCommandAgent<Probe> {

        RecordingAgent(CommandRuntime runtime, String type, CommandPayloadRedactor redactor) {
            super(runtime, CommandDescriptor.builder()
                    .type(type)
                    .objectType("probe")
                    .payloadType(Probe.class)
                    .redactor(redactor)
                    .build());
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
