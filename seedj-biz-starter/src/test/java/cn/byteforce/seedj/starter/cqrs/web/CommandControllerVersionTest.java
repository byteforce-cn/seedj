package cn.byteforce.seedj.starter.cqrs.web;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandContext;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.lock.LockProvider;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.starter.web.ContractErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** If-Match / expected_version 与 409 + Retry-After。 */
class CommandControllerVersionTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000031");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("probe", "probe_object")
            .field("name", FieldType.TEXT)
            .build();

    private final Clock clock = () -> NOW;

    private final InMemoryCommandStore commands = new InMemoryCommandStore();

    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);

    private final CallerProvider callers = () -> Optional.of(new Caller(ACTOR.toString(),
            "alice", "default", Set.of(), Set.of("probe.write"), Set.of(), Set.of("pwd"),
            "urn:seedj:acr:pwd", Map.of()));

    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, OutboxWriter.discard(),
            TemporalCommandAudit.noop()));

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commands.clear();
        objects.clear();
        objects.append(AppendRequest.create("probe", ID, NOW, null, ACTOR,
                Map.of("name", "v1")));
        CommandRegistration registration = new CommandRegistration(CommandDescriptor.builder()
                .type("probe.update")
                .objectType("probe")
                .payloadType(ProbeCommand.class)
                .permission("probe.write")
                .build(), new VersionHandler(), SCHEMA, Set.of("target"));
        CommandCatalog catalog = new CommandCatalog(List.of(registration),
                CommandPolicy.permissions(), runtime,
                (objectType, caller) -> ScopePredicate.all());
        mvc = MockMvcBuilders.standaloneSetup(new CommandController(catalog, commands, callers,
                        CallerIdCodec.uuid()))
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    private static String body(Long expectedVersion, String key) {
        String version = expectedVersion == null ? "" : ",\"expected_version\":" + expectedVersion;
        return """
                {"type":"probe.update",
                 "payload":{"target":"%s","name":"v2"%s},
                 "idempotency_key":"%s"}
                """.formatted(ID, version, key);
    }

    @Test
    @DisplayName("If-Match 过期：409 EIS-409-002 + Retry-After")
    void staleIfMatchReturns409WithRetryAfter() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "v-1")))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("EIS-409-002"));
    }

    @Test
    @DisplayName("header 与 body 不一致：400")
    void ifMatchAndBodyMustAgree() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, "v-2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("If-Match 命中：202 且版本递增")
    void matchingIfMatchSucceeds() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "v-3")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("非法 If-Match：400")
    void invalidIfMatchIs400() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .header("If-Match", "\"abc\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "v-4")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    record ProbeCommand(UUID target, String name) {
    }

    static final class VersionHandler implements CommandHandler<ProbeCommand> {

        @Override
        public UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        public AppendRequest decide(ProbeCommand command, CommandContext ctx) {
            return AppendRequest.correction("probe", ctx.targetId(), ctx.now(), null,
                    ctx.actor(), Map.of("name", command.name()), ctx.expectedVersion());
        }
    }
}
