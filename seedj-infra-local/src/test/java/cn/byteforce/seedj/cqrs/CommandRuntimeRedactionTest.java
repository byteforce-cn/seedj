package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
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

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            null, null, null, null, null, null);

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("probe", "probe_object")
            .field("name", FieldType.TEXT)
            .field("secret", FieldType.TEXT)
            .writeOnly("token", FieldType.TEXT)
            .build();

    private final AtomicReference<Instant> now = new AtomicReference<>(
            Instant.parse("2026-10-08T09:00:00Z"));
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));

    private final CommandRegistration redacting = registration("probe.update", payload -> {
        Map<String, Object> redacted = new LinkedHashMap<>(payload);
        redacted.remove("secret");
        return redacted;
    });
    private final CommandRegistration plain = registration("probe.plain",
            CommandPayloadRedactor.noop());
    private final CommandRegistration redacted = registration("probe.redacted",
            CommandPayloadRedactor.noop(), java.util.Set.of("target"));

    @Test
    @DisplayName("命令记录不含原始字段，重放仍命中幂等")
    void commandRecordIsRedactedAndReplayWorks() {
        CommandReceipt first = runtime.execute(submission("probe.update", "k-1", "raw-1", ID),
                redacting, CALLER, ScopePredicate.all());

        assertThat(commands.findByIdempotencyKey(ACTOR, "k-1")).get()
                .satisfies(record -> assertThat(record.payload()).doesNotContainKey("secret"));
        CommandReceipt replay = runtime.execute(submission("probe.update", "k-1", "raw-1", ID),
                redacting, CALLER, ScopePredicate.all());
        assertThat(replay.commandId()).isEqualTo(first.commandId());
    }

    @Test
    @DisplayName("同键不同原始字段仍触发幂等冲突")
    void fingerprintStillUsesRawPayload() {
        runtime.execute(submission("probe.update", "k-2", "raw-1", ID), redacting, CALLER,
                ScopePredicate.all());

        assertThatThrownBy(() -> runtime.execute(
                submission("probe.update", "k-2", "raw-2", ID), redacting, CALLER,
                ScopePredicate.all()))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("writeOnly 自动剔除；命令专用字段按声明决定是否落记录")
    void writeOnlyIsAlwaysRedactedAndCommandFieldsAreExplicit() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", ID.toString());
        payload.put("secret", "keep-me");
        payload.put("name", "Alice");
        payload.put("token", "do-not-store");

        runtime.execute(new CommandSubmission("probe.plain", payload, "k-5", List.of(ID),
                ACTOR), plain, CALLER, ScopePredicate.all());
        UUID second = UUID.fromString("018f2e3a-1234-7000-8000-000000000002");
        payload.put("target", second.toString());
        runtime.execute(new CommandSubmission("probe.redacted", payload, "k-6", List.of(second),
                ACTOR), redacted, CALLER, ScopePredicate.all());

        assertThat(commands.findByIdempotencyKey(ACTOR, "k-5")).get()
                .satisfies(record -> assertThat(record.payload())
                        .doesNotContainKey("token")
                        .containsEntry("target", ID.toString())
                        .containsEntry("secret", "keep-me")
                        .containsEntry("name", "Alice"));
        assertThat(commands.findByIdempotencyKey(ACTOR, "k-6")).get()
                .satisfies(record -> assertThat(record.payload())
                        .doesNotContainKeys("token", "target")
                        .containsEntry("secret", "keep-me"));
    }

    @Test
    @DisplayName("两个注册项的 redactor 互不影响")
    void redactorsArePerRegistration() {
        runtime.execute(submission("probe.update", "k-3", "raw-3", ID), redacting, CALLER,
                ScopePredicate.all());
        runtime.execute(submission("probe.plain", "k-4", "raw-4",
                UUID.fromString("018f2e3a-1234-7000-8000-000000000002")), plain, CALLER,
                ScopePredicate.all());

        assertThat(commands.findByIdempotencyKey(ACTOR, "k-3")).get()
                .satisfies(record -> assertThat(record.payload()).doesNotContainKey("secret"));
        assertThat(commands.findByIdempotencyKey(ACTOR, "k-4")).get()
                .satisfies(record -> assertThat(record.payload()).containsEntry("secret", "raw-4"));
    }

    private static CommandRegistration registration(String type, CommandPayloadRedactor redactor) {
        return registration(type, redactor, java.util.Set.of());
    }

    private static CommandRegistration registration(String type, CommandPayloadRedactor redactor,
                                                   java.util.Set<String> redactedFields) {
        CommandDescriptor descriptor = CommandDescriptor.builder()
                .type(type)
                .objectType("probe")
                .payloadType(Probe.class)
                .redactor(redactor)
                .build();
        return new CommandRegistration(descriptor, new ProbeHandler(), SCHEMA,
                java.util.Set.of("target"), redactedFields);
    }

    private static CommandSubmission submission(String type, String key, String secret, UUID target) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target.toString());
        payload.put("secret", secret);
        payload.put("name", "Alice");
        return new CommandSubmission(type, payload, key, List.of(target), ACTOR);
    }

    static final class ProbeHandler implements CommandHandler<Probe> {

        @Override
        public UUID targetId(Probe command) {
            return command.target();
        }

        @Override
        public AppendRequest decide(Probe command, CommandContext ctx) {
            return AppendRequest.create("probe", command.target(), ctx.now(), null, ctx.actor(),
                    Map.of("name", command.name()));
        }
    }

    record Probe(UUID target, String secret, String name) {
    }
}
