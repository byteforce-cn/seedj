package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendKind;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.event.CommandEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommandContextTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final Caller CALLER =
            new Caller(ACTOR.toString(), "alice", "default", null, null, null, null, null, null);

    private static ObjectSchema schema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .field("name", FieldType.TEXT)
                .field("status", FieldType.TEXT)
                .build();
    }

    private static TemporalRow current() {
        return new TemporalRow(
                "employee",
                new ObjectVersion(ID, 2, NOW, null, NOW, null, ACTOR, NOW, null, null),
                Map.of("name", "old", "status", "ACTIVE"));
    }

    private static CommandContext context(Optional<TemporalRow> current) {
        return new CommandContext(
                UUID.randomUUID(),
                ID,
                current,
                NOW,
                ACTOR,
                CALLER,
                ScopePredicate.all(),
                schema(),
                () -> UUID.randomUUID());
    }

    @Test
    void eventKeysAreCommandScopedAndIncrementing() {
        CommandContext context = context(Optional.empty());

        CommandEvent first = context.event("hr.employee.created", Map.of());
        CommandEvent second = context.event("hr.employee.created", Map.of());

        assertThat(first.idempotencyKey())
                .isEqualTo(context.commandId() + ":hr.employee.created:0");
        assertThat(second.idempotencyKey())
                .isEqualTo(context.commandId() + ":hr.employee.created:1");
        assertThat(first.subjects()).containsExactly(ID);
        assertThat(first.actor()).isEqualTo(ACTOR);
        assertThat(first.recordedAt()).isEqualTo(NOW);
    }

    @Test
    void mergeRejectsUnknownFields() {
        CommandContext context = context(Optional.of(current()));
        AbstractCommandHandler<String> handler =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        return correct(ctx, merge(ctx, Map.of("unknown", "x")));
                    }
                };

        assertThatThrownBy(() -> handler.decide("ignored", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void mergeIgnoresNulls() {
        CommandContext context = context(Optional.of(current()));
        AbstractCommandHandler<String> handler =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        java.util.Map<String, Object> changes = new java.util.LinkedHashMap<>();
                        changes.put("name", "new");
                        changes.put("status", null);
                        return correct(ctx, merge(ctx, changes));
                    }
                };

        AppendRequest request = handler.decide("ignored", context);

        assertThat(request.fields()).containsEntry("name", "new").containsEntry("status", "ACTIVE");
    }

    @Test
    void requireCurrentFailsWithObjectNotFound() {
        AbstractCommandHandler<String> handler =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        requireCurrent(ctx);
                        return correct(ctx, Map.of());
                    }
                };

        assertThatThrownBy(() -> handler.decide("ignored", context(Optional.empty())))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND));
    }

    @Test
    void createAndCorrectUseContextTarget() {
        CommandContext context = context(Optional.of(current()));
        AbstractCommandHandler<String> handler =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        return create(ctx, NOW.minusSeconds(60), Map.of("name", "new"));
                    }
                };

        AppendRequest created = handler.decide("ignored", context);
        assertThat(created.kind()).isEqualTo(AppendKind.CREATE);
        assertThat(created.id()).isEqualTo(ID);
        assertThat(created.objectType()).isEqualTo("employee");
        assertThat(created.validFrom()).isEqualTo(NOW.minusSeconds(60));

        AbstractCommandHandler<String> corrector =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        return correct(ctx, Map.of("name", "new"));
                    }
                };

        AppendRequest corrected = corrector.decide("ignored", context);
        assertThat(corrected.kind()).isEqualTo(AppendKind.CORRECTION);
        assertThat(corrected.id()).isEqualTo(ID);
    }

    @Test
    void rejectBuildsContractException() {
        AbstractCommandHandler<String> handler =
                new AbstractCommandHandler<>() {
                    @Override
                    public UUID targetId(String command) {
                        return ID;
                    }

                    @Override
                    public AppendRequest decide(String command, CommandContext ctx) {
                        throw reject(ContractErrorCode.COMMAND_REJECTED, "not allowed");
                    }
                };

        assertThatThrownBy(() -> handler.decide("ignored", context(Optional.empty())))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.COMMAND_REJECTED);
                            assertThat(error.reason()).isEqualTo("not allowed");
                        });
    }
}
