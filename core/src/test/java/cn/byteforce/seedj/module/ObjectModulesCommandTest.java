package cn.byteforce.seedj.module;

import cn.byteforce.seedj.cqrs.CommandHandler;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.FieldType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectModulesCommandTest {

    private static final CommandHandler<Object> HANDLER = new CommandHandler<>() {
        @Override
        public UUID targetId(Object command) {
            return UUID.randomUUID();
        }

        @Override
        public AppendRequest decide(Object command,
                                    cn.byteforce.seedj.cqrs.CommandContext ctx) {
            return AppendRequest.create("employee", ctx.targetId(), ctx.now(), null,
                    ctx.actor(), Map.of());
        }
    };

    @Test
    void commandDeclarationBuildsRegistration() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .creates()
                    .emits("hr.employee.created");

        ObjectModuleRegistry registry = modules.freeze();

        assertThat(registry.commands()).hasSize(1);
        CommandRegistration registration = registry.commands().get(0);
        assertThat(registration.descriptor().type()).isEqualTo("hr.employee.create");
        assertThat(registration.descriptor().permission()).isEqualTo("hr.employee.write");
        assertThat(registration.handler()).isSameAs(HANDLER);
        assertThat(registration.creates()).isTrue();
        assertThat(registry.commandDeclarations().get(0).creates()).isTrue();
        assertThat(registry.commandDeclarations().get(0).emits())
                .containsExactly("hr.employee.created");
    }

    @Test
    void duplicateCommandTypeFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write").handler(HANDLER);
        modules.object("employee2", "hr_employee_2")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write").handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate command type");
    }

    @Test
    void missingPermissionFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void missingHandlerFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreatePayload.class)
                    .permission("hr.employee.write");

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handler");
    }

    @Test
    void redactedCommandFieldIsExplicit() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .redactedCommandField("id_number");

        ObjectModuleRegistry registry = modules.freeze();
        CommandRegistration registration = registry.commands().get(0);

        assertThat(registration.commandFields()).containsExactly("id_number");
        assertThat(registration.redactedFields()).containsExactly("id_number");
        assertThat(registry.commandDeclarations().get(0).redactedFields())
                .containsExactly("id_number");
    }

    @Test
    void payloadFieldOutsideSchemaFailsAtFreeze() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write").handler(HANDLER);

        assertThatThrownBy(modules::freeze)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id_number");
    }

    @Test
    void commandFieldExemptsPayloadField() {
        ObjectModules modules = ObjectModules.create();
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("name", FieldType.TEXT)
                .command("hr.employee.create", CreateExtraPayload.class)
                    .permission("hr.employee.write")
                    .handler(HANDLER)
                    .commandField("id_number");

        assertThat(modules.freeze().commands()).hasSize(1);
    }

    record CreatePayload(String name) {
    }

    record CreateExtraPayload(String name, String idNumber) {
    }
}
