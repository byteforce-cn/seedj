package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.ObjectSchema;

import java.util.Objects;
import java.util.Set;

/**
 * 一条命令的注册项：描述、处理器与对象 schema。
 *
 * <p>schema 随注册项冻结，运行时用它校验写范围；这样 handler 与运行时共享同一份字段声明，
 * 不需要在 cqrs 层反向依赖 module 注册表。</p>
 *
 * @param descriptor    命令描述
 * @param handler       处理器
 * @param schema        对象 schema
 * @param commandFields 命令专用、不在对象 schema 中的字段（如 {@code id_number}）
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                                  ObjectSchema schema, Set<String> commandFields) {

    public CommandRegistration {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        handler = Objects.requireNonNull(handler, "handler");
        schema = Objects.requireNonNull(schema, "schema");
        commandFields = commandFields == null ? Set.of() : Set.copyOf(commandFields);
        if (!descriptor.objectType().equals(schema.objectType())) {
            throw new IllegalArgumentException("descriptor objectType "
                    + descriptor.objectType() + " does not match schema " + schema.objectType());
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
        this(descriptor, handler, schema, Set.of());
    }
}
