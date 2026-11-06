package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.clock.TestClock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.infra.local.cqrs.InMemoryCommandStore;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.infra.local.domain.InMemoryTemporalStore;
import cn.byteforce.seedj.infra.local.id.Uuid7IdGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命令目录：启动期完整性 + 命令级授权。 */
class CommandCatalogTest {

    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");
    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000031");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");

    private final TestClock clock = new TestClock(T0);
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final CommandRuntime runtime = new CommandRuntime(
            CommandRuntime.Options.forTests(clock, new Uuid7IdGenerator(clock), commands, objects));

    @Test
    @DisplayName("descriptor 缺权限：构造目录立即失败")
    void missingPermissionFailsAtStartup() {
        ProbeAgent noPermission = new ProbeAgent(runtime, "a.write",
                CommandDescriptor.builder()
                        .type("probe.no_permission")
                        .objectType("probe")
                        .payloadType(Probe.class)
                        .build());

        assertThatThrownBy(() -> new CommandCatalog(List.of(noPermission),
                CommandPolicy.permissions()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare a permission");
    }

    @Test
    @DisplayName("未知类型：无效载荷，不静默回退")
    void unknownTypeIsInvalidPayload() {
        CommandCatalog catalog = catalog(new ProbeAgent(runtime, "a.write", descriptor("probe.one")));

        assertThatThrownBy(() -> catalog.execute(
                new CommandSubmission("probe.unknown", Map.of("target", ID.toString()), "k-1",
                        List.of(ID), ACTOR), caller("a.write")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("无权限：403，且未写入对象")
    void unauthorizedIsForbidden() {
        CommandCatalog catalog = catalog(new ProbeAgent(runtime, "a.write", descriptor("probe.one")));

        assertThatThrownBy(() -> catalog.execute(
                new CommandSubmission("probe.one", Map.of("target", ID.toString()), "k-2",
                        List.of(ID), ACTOR), caller("a.read")))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));

        assertThat(objects.history("probe", ID)).isEmpty();
        assertThat(commands.findByIdempotencyKey("k-2")).isEmpty();
    }

    @Test
    @DisplayName("有权限：执行并暴露全部 descriptor")
    void authorizedExecutesAndDescriptorsAreListed() {
        ProbeAgent agent = new ProbeAgent(runtime, "a.write", descriptor("probe.one"));
        CommandCatalog catalog = catalog(agent);

        assertThatCode(() -> catalog.execute(
                new CommandSubmission("probe.one", Map.of("target", ID.toString()), "k-3",
                        List.of(ID), ACTOR), caller("a.write")))
                .doesNotThrowAnyException();
        assertThat(catalog.descriptors()).containsExactly(agent.descriptor());
        assertThat(catalog.types()).containsExactly("probe.one");
    }

    private static CommandCatalog catalog(TemporalCommandAgent<?>... agents) {
        return new CommandCatalog(List.of(agents), CommandPolicy.permissions());
    }

    private static CommandDescriptor descriptor(String type) {
        return CommandDescriptor.builder()
                .type(type)
                .objectType("probe")
                .payloadType(Probe.class)
                .permission("a.write")
                .build();
    }

    private static Caller caller(String permission) {
        return new Caller("u-1", "alice", "default", Set.of(), Set.of(permission), Set.of(),
                Set.of(Caller.DEFAULT_AMR), Caller.DEFAULT_ACR, Map.of());
    }

    record Probe(UUID target) {}

    static final class ProbeAgent extends TemporalCommandAgent<Probe> {

        ProbeAgent(CommandRuntime runtime, String permission, CommandDescriptor descriptor) {
            super(runtime, descriptor);
        }

        @Override
        protected UUID targetId(Probe command) {
            return command.target();
        }

        @Override
        protected AppendRequest append(Probe command, Optional<TemporalRow> current, Instant now,
                                       UUID actor) {
            return AppendRequest.create("probe", command.target(), now, null, actor,
                    Map.of("target", command.target().toString()));
        }
    }
}
