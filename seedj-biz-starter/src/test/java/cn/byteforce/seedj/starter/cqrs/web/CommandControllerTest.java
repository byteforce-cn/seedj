package cn.byteforce.seedj.starter.cqrs.web;

import cn.byteforce.seedj.biz.web.ContractErrorHandler;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.core.security.CallerIdCodec;
import cn.byteforce.seedj.core.security.CallerProvider;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandPolicy;
import cn.byteforce.seedj.cqrs.CqrsErrorCode;
import cn.byteforce.seedj.cqrs.CqrsException;
import cn.byteforce.seedj.cqrs.CommandCatalog;
import cn.byteforce.seedj.cqrs.CommandRuntime;
import cn.byteforce.seedj.cqrs.TemporalCommandAudit;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.cqrs.TemporalCommandAgent;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import cn.byteforce.seedj.lock.LockProvider;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 命令 HTTP 面的冻结形状：202/400/409、幂等重放、命令终态查询。
 */
class CommandControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000021");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final Clock clock = () -> T0;

    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);

    private final InMemoryCommandStore commands = new InMemoryCommandStore();

    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), LockProvider.disabled(), Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects,
            OutboxWriter.discard(), TemporalCommandAudit.noop()));

    private final AtomicReference<Caller> caller = new AtomicReference<>(caller(ACTOR.toString()));

    private final CallerProvider callers = () -> Optional.ofNullable(caller.get());

    private final CommandCatalog catalog = new CommandCatalog(
            List.of(new ProbeAgent(runtime), new CqrsRejectAgent(runtime)),
            CommandPolicy.permissions());

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commands.clear();
        objects.clear();
        caller.set(caller(ACTOR.toString()));
        mvc = MockMvcBuilders.standaloneSetup(new CommandController(catalog, commands, callers, CallerIdCodec.uuid()))
                .setControllerAdvice(new ContractErrorHandler())
                .build();
    }

    @Test
    @DisplayName("接受命令：202 + locator/version，不携带读模型")
    void acceptsCommandWith202AndLocatorVersion() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-1", "Alice", false)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.locator").value(ID.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.event_ids").isArray())
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    @DisplayName("同键同载荷重放：返回原 command_id，不产生第二版本")
    void replaysSameKeyWithOriginalResult() throws Exception {
        String first = mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-2", "Alice", false)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String commandId = com.jayway.jsonpath.JsonPath.read(first, "$.command_id");
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-2", "Alice", false)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command_id").value(commandId))
                .andExpect(jsonPath("$.version").value(1));

        org.assertj.core.api.Assertions.assertThat(objects.history("probe", ID, ScopePredicate.all())).hasSize(1);
    }

    @Test
    @DisplayName("同键不同载荷：409 + 原命令结果")
    void sameKeyDifferentPayloadIs409WithOriginalCommand() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-3", "Alice", false)))
                .andExpect(status().isAccepted());

        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-3", "Bob", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.idempotency_key").value("k-3"))
                .andExpect(jsonPath("$.original_command_id").isNotEmpty())
                .andExpect(jsonPath("$.original_result.status").value("accepted"));
    }

    @Test
    @DisplayName("业务拒绝：400 + rejected 冻结体")
    void businessRejectionIs400WithFrozenBody() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-4", "Alice", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.code").value("EIS-400-003"))
                .andExpect(jsonPath("$.reason").value("rejected by probe"))
                .andExpect(jsonPath("$.command_id").isNotEmpty());
    }

    @Test
    @DisplayName("GET 命令终态：accepted / rejected 都能查")
    void getCommandReturnsTerminalStatus() throws Exception {
        String accepted = mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-5", "Alice", false)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String acceptedId = com.jayway.jsonpath.JsonPath.read(accepted, "$.command_id");

        mvc.perform(get("/commands/" + acceptedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.command_id").value(acceptedId))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.event_ids").isArray());

        String rejected = mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-6", "Alice", true)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String rejectedId = com.jayway.jsonpath.JsonPath.read(rejected, "$.command_id");

        mvc.perform(get("/commands/" + rejectedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.code").value("EIS-400-003"))
                .andExpect(jsonPath("$.reason").value("rejected by probe"));
    }

    @Test
    @DisplayName("未知命令类型：400 + invalid payload")
    void unknownCommandTypeIs400() throws Exception {
        mvc.perform(post("/commands/probe.unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"probe.unknown","payload":{},"idempotency_key":"k-7"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("缺幂等键：400 + invalid payload")
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"probe.update","payload":{"target":"%s","name":"Alice"}}
                                """.formatted(ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("targets 含 null：400 契约信封")
    void nullTargetIs400() throws Exception {
        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"probe.update","payload":{"target":"%s"},
                                 "idempotency_key":"k-null","targets":[null]}
                                """.formatted(ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.code").value("EIS-400-001"));
    }

    @Test
    @DisplayName("事件溯源业务拒绝：400 EIS-400-002，不是 503")
    void cqrsRejectionIs400Not503() throws Exception {
        mvc.perform(post("/commands/probe.cqrs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"probe.cqrs",
                                 "payload":{"target":"%s","name":"Alice","reject":false},
                                 "idempotency_key":"k-cqrs"}
                                """.formatted(ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EIS-400-002"));
    }

    @Test
    @DisplayName("调用方 ID 不是 UUID：401 契约信封")
    void callerWithoutUuidIssuerIs401() throws Exception {
        caller.set(caller("alice"));

        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-8", "Alice", false)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EIS-401-001"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-401-001"));
    }

    @Test
    @DisplayName("无命令权限：403 EIS-403-001，不进入锁与事务")
    void unauthorizedCommandIs403() throws Exception {
        caller.set(callerWithoutPermission(ACTOR.toString()));

        mvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-9", "Alice", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EIS-403-001"))
                .andExpect(jsonPath("$.details.reason_code").value("EIS-403-001"));

        org.assertj.core.api.Assertions.assertThat(objects.history("probe", ID, ScopePredicate.all())).isEmpty();
    }

    @Test
    @DisplayName("非 UUID 主体 + nameUuid 编解码：命令可执行")
    void nameUuidCodecAcceptsNonUuidCaller() throws Exception {
        caller.set(new Caller("u-1", "alice", "default", Set.of(), Set.of("probe.write"),
                Set.of(), Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of()));
        MockMvc nameUuidMvc = MockMvcBuilders.standaloneSetup(
                        new CommandController(catalog, commands, callers, CallerIdCodec.nameUuid()))
                .setControllerAdvice(new ContractErrorHandler())
                .build();

        nameUuidMvc.perform(post("/commands/probe.update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("k-10", "Alice", false)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("未知命令的 GET：404 契约信封")
    void unknownCommandStatusIs404() throws Exception {
        mvc.perform(get("/commands/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EIS-404-001"))
                .andExpect(jsonPath("$.message").value("command not found"));
    }

    private static String body(String key, String name, boolean reject) {
        return """
                {"type":"probe.update",
                 "payload":{"target":"%s","name":"%s","reject":%s},
                 "idempotency_key":"%s",
                 "targets":["%s"]}
                """.formatted(ID, name, reject, key, ID);
    }

    private static Caller caller(String id) {
        return new Caller(id, "alice", "default", Set.of(), Set.of("probe.write"), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of());
    }

    private static Caller callerWithoutPermission(String id) {
        return new Caller(id, "bob", "default", Set.of(), Set.of("probe.read"), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of());
    }

    static final class CqrsRejectAgent extends TemporalCommandAgent<ProbeCommand> {

        CqrsRejectAgent(CommandRuntime runtime) {
            super(runtime, CommandDescriptor.builder()
                    .type("probe.cqrs")
                    .objectType("probe")
                    .payloadType(ProbeCommand.class)
                    .permission("probe.write")
                    .build());
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
            throw new CqrsException(CqrsErrorCode.COMMAND_REJECTED, "rejected by projector");
        }
    }

    static final class ProbeAgent extends TemporalCommandAgent<ProbeCommand> {

        ProbeAgent(CommandRuntime runtime) {
            super(runtime, CommandDescriptor.builder()
                    .type("probe.update")
                    .objectType("probe")
                    .payloadType(ProbeCommand.class)
                    .permission("probe.write")
                    .build());
        }

        @Override
        protected UUID targetId(ProbeCommand command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(ProbeCommand command, Optional<TemporalRow> current,
                                       Instant now, UUID actor) {
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
    }

    record ProbeCommand(UUID target, String name, boolean reject) {
    }
}
