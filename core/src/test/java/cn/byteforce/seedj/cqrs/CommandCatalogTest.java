package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandCatalogTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Caller CALLER = new Caller(ACTOR.toString(), "alice", "default",
            null, Set.of("probe.write"), null, null, null, null);

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("probe", "probe_object")
            .field("employee_no", FieldType.TEXT)
            .field("org_unit_id", FieldType.TEXT)
            .build();

    private static CommandDescriptor descriptor(String permission) {
        return CommandDescriptor.builder()
                .type("probe.update")
                .objectType("probe")
                .payloadType(CatalogPayload.class)
                .permission(permission)
                .build();
    }

    private static CommandRegistration registration(String permission) {
        return new CommandRegistration(descriptor(permission), new Handler(), SCHEMA);
    }

    private static CommandRuntime runtime() {
        AtomicLong sequence = new AtomicLong();
        return new CommandRuntime(new CommandRuntime.Options(
                cn.byteforce.seedj.cqrs.spi.TransactionRunner.direct(),
                cn.byteforce.seedj.lock.LockProvider.disabled(),
                java.time.Duration.ofSeconds(30),
                new cn.byteforce.seedj.core.clock.TestClock(
                        java.time.Instant.parse("2026-11-06T09:00:00Z")),
                () -> new UUID(0, sequence.incrementAndGet()),
                new CommandRuntimeTest.FakeCommands(), new CommandRuntimeTest.FakeObjects(),
                cn.byteforce.seedj.outbox.OutboxWriter.discard(),
                TemporalCommandAudit.noop()));
    }

    private static CommandSubmission submission() {
        return new CommandSubmission("probe.update", Map.of("employee_no", "E-1"), "key-1",
                List.of(), ACTOR);
    }

    @Test
    void executeAuthorizesAndDelegatesToRuntime() {
        CommandCatalog catalog = new CommandCatalog(List.of(registration("probe.write")),
                CommandPolicy.permissions(), runtime(), (type, caller) -> ScopePredicate.all());

        CommandReceipt receipt = catalog.execute(submission(), CALLER);

        assertThat(receipt.status()).isEqualTo(CommandStatus.ACCEPTED);
    }

    @Test
    void unknownTypeIsInvalidPayload() {
        CommandCatalog catalog = new CommandCatalog(List.of(registration("probe.write")),
                CommandPolicy.permissions(), runtime(), (type, caller) -> ScopePredicate.all());

        CommandSubmission missing = new CommandSubmission("probe.missing", Map.of("employee_no", "E-1"), "key",
                List.of(), ACTOR);
        assertThatThrownBy(() -> catalog.execute(missing, CALLER))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    void missingPermissionFailsAtConstruction() {
        assertThatThrownBy(() -> new CommandCatalog(List.of(registration(null)),
                CommandPolicy.permissions(), runtime(), (type, caller) -> ScopePredicate.all()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void duplicateTypeFailsAtConstruction() {
        assertThatThrownBy(() -> new CommandCatalog(
                List.of(registration("probe.write"), registration("probe.write")),
                CommandPolicy.permissions(), runtime(), (type, caller) -> ScopePredicate.all()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate command type");
    }

    @Test
    void writeScopeProviderIsCalled() {
        AtomicReference<String> type = new AtomicReference<>();
        CommandCatalog catalog = new CommandCatalog(List.of(registration("probe.write")),
                CommandPolicy.permissions(), runtime(), (objectType, caller) -> {
                    type.set(objectType);
                    return ScopePredicate.all();
                });

        catalog.execute(submission(), CALLER);

        assertThat(type.get()).isEqualTo("probe");
    }

    record CatalogPayload(String employeeNo) {
    }

    static final class Handler implements CommandHandler<CatalogPayload> {

        @Override
        public UUID targetId(CatalogPayload command) {
            return ID;
        }

        @Override
        public AppendRequest decide(CatalogPayload command, CommandContext ctx) {
            return AppendRequest.create("probe", ID, ctx.now(), null, ctx.actor(),
                    Map.of("employee_no", "E-1", "org_unit_id", "D01"));
        }
    }
}
