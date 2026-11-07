package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.ObjectSchema;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条命令的注册项：描述、处理器与对象 schema。
 *
 * <p>schema 随注册项冻结，运行时用它校验写范围；这样 handler 与运行时共享同一份字段声明，
 * 不需要在 cqrs 层反向依赖 module 注册表。</p>
 *
 * @param descriptor     命令描述
 * @param handler        处理器
 * @param schema         对象 schema
 * @param commandFields  命令专用、不在对象 schema 中的字段（如 {@code id_number}）
 * @param redactedFields 命令专用且不得落命令记录的字段（如原始证件号）
 * @param emits          声明的事件类型；为空表示不限制
 * @param creates        是否为创建型命令：对象已存在时运行时失败关闭
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                                  ObjectSchema schema, Set<String> commandFields,
                                  Set<String> redactedFields, List<String> emits,
                                  boolean creates) {

    public CommandRegistration {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        handler = Objects.requireNonNull(handler, "handler");
        schema = Objects.requireNonNull(schema, "schema");
        commandFields = commandFields == null ? Set.of() : Set.copyOf(commandFields);
        redactedFields = redactedFields == null ? Set.of() : Set.copyOf(redactedFields);
        emits = emits == null ? List.of() : List.copyOf(emits);
        if (!descriptor.objectType().equals(schema.objectType())) {
            throw new IllegalArgumentException("descriptor objectType "
                    + descriptor.objectType() + " does not match schema " + schema.objectType());
        }
        for (String field : commandFields) {
            if (schema.field(field).isPresent()) {
                throw new IllegalArgumentException("command-only field is declared in schema: "
                        + field);
            }
        }
        for (String field : redactedFields) {
            if (!commandFields.contains(field)) {
                throw new IllegalArgumentException(
                        "redacted field must be a command-only field: " + field);
            }
        }
    }

    /**
     * 无命令专用字段的注册项。
     *
     * @param descriptor 命令描述
     * @param handler    处理器
     * @param schema     对象 schema
     */
    public CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                               ObjectSchema schema) {
        this(descriptor, handler, schema, Set.of(), Set.of(), List.of(), false);
    }

    /**
     * 命令专用字段全部可审计的注册项。
     *
     * @param descriptor    命令描述
     * @param handler       处理器
     * @param schema        对象 schema
     * @param commandFields 命令专用字段
     */
    public CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                               ObjectSchema schema, Set<String> commandFields) {
        this(descriptor, handler, schema, commandFields, Set.of(), List.of(), false);
    }

    /**
     * 声明事件类型但仍使用默认脱敏集合的注册项。
     *
     * @param descriptor    命令描述
     * @param handler       处理器
     * @param schema        对象 schema
     * @param commandFields 命令专用字段
     * @param redactedFields 命令专用且不得落命令记录的字段
     */
    public CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                               ObjectSchema schema, Set<String> commandFields,
                               Set<String> redactedFields) {
        this(descriptor, handler, schema, commandFields, redactedFields, List.of(), false);
    }

    /**
     * 声明事件类型但仍使用默认脱敏集合的注册项。
     *
     * @param descriptor    命令描述
     * @param handler       处理器
     * @param schema        对象 schema
     * @param commandFields 命令专用字段
     * @param redactedFields 命令专用且不得落命令记录的字段
     * @param emits         声明的事件类型
     */
    public CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                               ObjectSchema schema, Set<String> commandFields,
                               Set<String> redactedFields, List<String> emits) {
        this(descriptor, handler, schema, commandFields, redactedFields, emits, false);
    }
}
