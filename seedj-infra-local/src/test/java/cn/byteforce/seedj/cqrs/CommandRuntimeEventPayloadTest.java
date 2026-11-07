package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.event.CommandEvent;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.OutboxWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 事件载荷由 schema 白名单收口，handler 不需要手工过滤敏感字段。 */
class CommandRuntimeEventPayloadTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");
    private static final Caller CALLER =
            new Caller(ACTOR.toString(), "alice", "default", null, null, null, null, null, null);
    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("probe", "probe_object")
                    .field("name", FieldType.TEXT)
                    .writeOnly("token", FieldType.TEXT)
                    .build();

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-06T09:00:00Z"));
    private final Clock clock = now::get;
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final AtomicReference<OutboxRecord> outbox = new AtomicReference<>();
    private final AtomicReference<List<CommandEvent>> audited = new AtomicReference<>();

    @Test
    @DisplayName("事件载荷自动剔除 writeOnly 字段")
    void eventPayloadDropsWriteOnlyFields() {
        CommandRuntime runtime = runtime((record) -> outbox.set(record));

        runtime.execute(
                new CommandSubmission(
                        "probe.update",
                        Map.of("target", ID.toString(), "name", "Alice", "token", "secret"),
                        "k-1",
                        List.of(ID),
                        ACTOR),
                registration(FilteringHandler::new),
                CALLER,
                ScopePredicate.all());

        String payload = new String(outbox.get().payload(), StandardCharsets.UTF_8);
        assertThat(payload).contains("\"name\":\"Alice\"");
        assertThat(payload).doesNotContain("token").doesNotContain("secret");
        assertThat(audited.get()).hasSize(1);
        assertThat(audited.get().get(0).payload())
                .containsEntry("name", "Alice")
                .doesNotContainKey("token");
    }

    @Test
    @DisplayName("handler 发出未声明的事件类型时失败关闭")
    void undeclaredEventTypeFailsClosed() {
        CommandRuntime runtime = runtime((record) -> outbox.set(record));

        assertThatThrownBy(
                        () ->
                                runtime.execute(
                                        new CommandSubmission(
                                                "probe.update",
                                                Map.of("target", ID.toString(), "name", "Alice"),
                                                "k-3",
                                                List.of(ID),
                                                ACTOR),
                                        registration(
                                                BrokenEventHandler::new, List.of("probe.updated")),
                                        CALLER,
                                        ScopePredicate.all()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undeclared");
        assertThat(outbox.get()).isNull();
    }

    @Test
    @DisplayName("事件载荷出现 schema 外字段时失败关闭，不静默丢弃")
    void unknownEventPayloadFieldFailsClosed() {
        CommandRuntime runtime = runtime((record) -> outbox.set(record));

        assertThatThrownBy(
                        () ->
                                runtime.execute(
                                        new CommandSubmission(
                                                "probe.update",
                                                Map.of("target", ID.toString(), "name", "Alice"),
                                                "k-2",
                                                List.of(ID),
                                                ACTOR),
                                        registration(BrokenHandler::new),
                                        CALLER,
                                        ScopePredicate.all()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown");
        assertThat(outbox.get()).isNull();
    }

    private CommandRuntime runtime(OutboxWriter writer) {
        return new CommandRuntime(
                new CommandRuntime.Options(
                        cn.byteforce.seedj.cqrs.spi.TransactionRunner.direct(),
                        cn.byteforce.seedj.lock.LockProvider.disabled(),
                        Duration.ofSeconds(30),
                        clock,
                        new Uuid7IdGenerator(clock),
                        commands,
                        objects,
                        writer,
                        (record, objectType, events) -> audited.set(events)));
    }

    private static CommandRegistration registration(
            java.util.function.Function<UUID, CommandHandler<Probe>> factory) {
        return registration(factory, List.of());
    }

    private static CommandRegistration registration(
            java.util.function.Function<UUID, CommandHandler<Probe>> factory, List<String> emits) {
        return new CommandRegistration(
                CommandDescriptor.builder()
                        .type("probe.update")
                        .objectType("probe")
                        .payloadType(Probe.class)
                        .build(),
                factory.apply(ID),
                SCHEMA,
                java.util.Set.of("target"),
                java.util.Set.of(),
                emits);
    }

    record Probe(UUID target, String name) {}

    private static final class FilteringHandler implements CommandHandler<Probe> {

        private final UUID id;

        private FilteringHandler(UUID id) {
            this.id = id;
        }

        @Override
        public UUID targetId(Probe command) {
            return id;
        }

        @Override
        public AppendRequest decide(Probe command, CommandContext ctx) {
            return AppendRequest.create(
                    "probe", id, ctx.now(), null, ctx.actor(), Map.of("name", command.name()));
        }

        @Override
        public List<CommandEvent> events(Probe command, AppendResult result, CommandContext ctx) {
            return List.of(
                    ctx.event("probe.updated", Map.of("name", command.name(), "token", "secret")));
        }
    }

    private static final class BrokenEventHandler implements CommandHandler<Probe> {

        private BrokenEventHandler(UUID id) {
            this.id = id;
        }

        private final UUID id;

        @Override
        public UUID targetId(Probe command) {
            return id;
        }

        @Override
        public AppendRequest decide(Probe command, CommandContext ctx) {
            return AppendRequest.create(
                    "probe", id, ctx.now(), null, ctx.actor(), Map.of("name", command.name()));
        }

        @Override
        public List<CommandEvent> events(Probe command, AppendResult result, CommandContext ctx) {
            return List.of(ctx.event("probe.other", Map.of("name", command.name())));
        }
    }

    private static final class BrokenHandler implements CommandHandler<Probe> {

        private final UUID id;

        private BrokenHandler(UUID id) {
            this.id = id;
        }

        @Override
        public UUID targetId(Probe command) {
            return id;
        }

        @Override
        public AppendRequest decide(Probe command, CommandContext ctx) {
            return AppendRequest.create(
                    "probe", id, ctx.now(), null, ctx.actor(), Map.of("name", command.name()));
        }

        @Override
        public List<CommandEvent> events(Probe command, AppendResult result, CommandContext ctx) {
            return List.of(
                    ctx.event("probe.updated", Map.of("name", command.name(), "unknown", "x")));
        }
    }
}
