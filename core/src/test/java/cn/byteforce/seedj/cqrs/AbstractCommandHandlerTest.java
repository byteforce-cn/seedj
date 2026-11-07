package cn.byteforce.seedj.cqrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.security.Caller;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命令 record 到 schema 字段的自动映射：加字段不再改 handler。 */
class AbstractCommandHandlerTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final Caller CALLER =
            new Caller(ACTOR.toString(), "alice", "default", null, null, null, null, null, null);

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("employee", "hr_employee")
                    .required("name", FieldType.TEXT)
                    .field("hire_date", FieldType.DATE)
                    .field("org_unit_id", FieldType.TEXT)
                    .writeOnly("id_number_hash", FieldType.TEXT)
                    .build();

    private static final CommandContext CONTEXT =
            new CommandContext(
                    UUID.randomUUID(),
                    ID,
                    Optional.empty(),
                    NOW,
                    ACTOR,
                    CALLER,
                    ScopePredicate.all(),
                    SCHEMA,
                    UUID::randomUUID);

    @Test
    @DisplayName("record 组件按 snake_case 映射到 schema，命令专用字段与 null 被跳过")
    void mapsRecordComponentsToSchemaFields() {
        ProbeHandler handler = new ProbeHandler();
        Probe command =
                new Probe(
                        "Alice",
                        LocalDate.parse("2026-10-01"),
                        "D01",
                        "hash",
                        "110101199001011234",
                        null);

        Map<String, Object> fields = handler.fields(CONTEXT, command);

        assertThat(fields)
                .containsExactly(
                        Map.entry("name", "Alice"),
                        Map.entry("hire_date", LocalDate.parse("2026-10-01")),
                        Map.entry("org_unit_id", "D01"),
                        Map.entry("id_number_hash", "hash"));
    }

    @Test
    @DisplayName("非 record 命令不能使用自动映射")
    void nonRecordCommandFails() {
        RawHandler handler = new RawHandler();

        assertThatThrownBy(() -> handler.fields(CONTEXT, "not-a-record"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("record");
    }

    private static final class ProbeHandler extends AbstractCommandHandler<Probe> {

        Map<String, Object> fields(CommandContext ctx, Probe command) {
            return schemaFields(ctx, command);
        }

        @Override
        public UUID targetId(Probe command) {
            return ID;
        }

        @Override
        public AppendRequest decide(Probe command, CommandContext ctx) {
            return create(ctx, NOW, schemaFields(ctx, command));
        }
    }

    private static final class RawHandler extends AbstractCommandHandler<Object> {

        Map<String, Object> fields(CommandContext ctx, Object command) {
            return schemaFields(ctx, command);
        }

        @Override
        public UUID targetId(Object command) {
            return ID;
        }

        @Override
        public AppendRequest decide(Object command, CommandContext ctx) {
            return create(ctx, NOW, Map.of());
        }
    }

    private record Probe(
            String name,
            LocalDate hireDate,
            String orgUnitId,
            String idNumberHash,
            String idNumber,
            String ignored) {}
}
