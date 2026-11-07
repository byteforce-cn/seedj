package cn.byteforce.seedj.module;

import cn.byteforce.seedj.core.utils.FieldNames;
import cn.byteforce.seedj.cqrs.CommandDescriptor;
import cn.byteforce.seedj.cqrs.CommandRegistration;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.ObjectSchema;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 冻结命令声明：把 DSL 的 {@link ObjectModules.CommandSpec} 转成描述、快照与注册项。
 *
 * <p>包内类型，只由 {@code ObjectModules.freeze()} 调用。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ObjectModuleCommands {

    private ObjectModuleCommands() {}

    static void register(
            ObjectModules.CommandSpec command,
            String objectType,
            ObjectSchema schema,
            Set<String> commandTypes,
            List<CommandDeclaration> declarations,
            List<CommandRegistration> registrations) {
        if (!commandTypes.add(command.type())) {
            throw new IllegalArgumentException("duplicate command type: " + command.type());
        }
        if (command.permission() == null || command.permission().isBlank()) {
            throw new IllegalArgumentException(
                    "command " + command.type() + " must declare a permission");
        }
        if ((command.handler() == null) == (command.agent() == null)) {
            throw new IllegalArgumentException(
                    "command " + command.type() + " must declare exactly one of handler or agent");
        }
        if (command.agent() != null
                && !command.payloadType().equals(command.agent().commandType())) {
            throw new IllegalArgumentException(
                    "command "
                            + command.type()
                            + " payload type "
                            + command.payloadType().getName()
                            + " does not match agent command type "
                            + command.agent().commandType().getName());
        }
        boolean creates =
                command.createsDeclared()
                        ? command.createsFlag()
                        : command.agent() != null && command.agent().isCreateCommand();
        if (command.agent() != null
                && command.createsDeclared()
                && command.createsFlag() != command.agent().isCreateCommand()) {
            throw new IllegalArgumentException(
                    "command " + command.type() + " creates flag does not match agent");
        }
        requirePayloadFields(command, schema);
        CommandDescriptor descriptor =
                CommandDescriptor.builder()
                        .type(command.type())
                        .objectType(objectType)
                        .payloadType(command.payloadType())
                        .permission(command.permission())
                        .validator(command.validator())
                        .redactor(command.redactor())
                        .build();
        declarations.add(
                new CommandDeclaration(
                        objectType,
                        command.type(),
                        command.payloadType(),
                        command.permission(),
                        command.handler(),
                        command.agent(),
                        creates,
                        command.emits(),
                        command.commandFields(),
                        command.redactedFields()));
        registrations.add(
                command.agent() == null
                        ? new CommandRegistration(
                                descriptor,
                                command.handler(),
                                schema,
                                command.commandFields(),
                                command.redactedFields(),
                                command.emits(),
                                creates)
                        : CommandRegistration.eventSourced(
                                descriptor,
                                command.agent(),
                                schema,
                                command.commandFields(),
                                command.redactedFields(),
                                command.emits()));
    }

    private static void requirePayloadFields(
            ObjectModules.CommandSpec command, ObjectSchema schema) {
        if (!command.payloadType().isRecord()) {
            throw new IllegalArgumentException(
                    "command payload must be a record: " + command.payloadType().getName());
        }
        Set<String> allowed = new LinkedHashSet<>(command.commandFields());
        for (FieldDefinition field : schema.fields()) {
            allowed.add(field.name());
        }
        for (RecordComponent component : command.payloadType().getRecordComponents()) {
            String field = FieldNames.snakeCase(component.getName());
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        "command " + command.type() + " payload field is not in schema: " + field);
            }
        }
    }
}
