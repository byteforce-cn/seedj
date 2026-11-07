package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.ObjectSchema;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一条命令的注册项：描述、处理器或事件溯源 agent、对象 schema。
 *
 * <p>schema 随注册项冻结，运行时用它校验写范围；这样 handler 与运行时共享同一份字段声明，
 * 不需要在 cqrs 层反向依赖 module 注册表。{@code handler} 与 {@code agent} 恰好一个：
 * 前者是双时间轴默认路径，后者是事件溯源执行模型。</p>
 *
 * @param descriptor     命令描述
 * @param handler        双时间轴处理器；事件溯源注册项为 {@code null}
 * @param agent          事件溯源命令代理；双时间轴注册项为 {@code null}
 * @param schema         对象 schema
 * @param commandFields  命令专用、不在对象 schema 中的字段（如 {@code id_number}）
 * @param redactedFields 命令专用且不得落命令记录的字段（如原始证件号）
 * @param emits          声明的事件类型；为空表示不限制
 * @param creates        是否为创建型命令：对象已存在时运行时失败关闭
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                                  CommandAgent<?, ?, ?> agent, ObjectSchema schema,
                                  Set<String> commandFields, Set<String> redactedFields,
                                  List<String> emits, boolean creates) {

    public CommandRegistration {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        schema = Objects.requireNonNull(schema, "schema");
        if ((handler == null) == (agent == null)) {
            throw new IllegalArgumentException(
                    "command " + descriptor.type() + " must declare exactly one of handler or agent");
        }
        commandFields = commandFields == null ? Set.of() : Set.copyOf(commandFields);
        redactedFields = redactedFields == null ? Set.of() : Set.copyOf(redactedFields);
        emits = emits == null ? List.of() : List.copyOf(emits);
        if (!descriptor.objectType().equals(schema.objectType())) {
            throw new IllegalArgumentException("descriptor objectType "
                    + descriptor.objectType() + " does not match schema " + schema.objectType());
        }
        if (agent != null) {
            if (!descriptor.payloadType().equals(agent.commandType())) {
                throw new IllegalArgumentException("command " + descriptor.type()
                        + " payload type " + descriptor.payloadType().getName()
                        + " does not match agent command type "
                        + agent.commandType().getName());
            }
            if (creates != agent.isCreateCommand()) {
                throw new IllegalArgumentException("command " + descriptor.type()
                        + " creates flag does not match agent");
            }
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
     * 是否为事件溯源注册项。
     *
     * @return {@code true} 表示经 agent 执行
     */
    public boolean isEventSourced() {
        return agent != null;
    }

    /**
     * 事件溯源注册项。
     *
     * @param descriptor    命令描述
     * @param agent         命令代理
     * @param schema        对象 schema
     * @param commandFields 命令专用字段
     * @param redactedFields 不得落命令记录的字段
     * @param emits         声明的事件类型
     * @return 注册项
     */
    public static CommandRegistration eventSourced(CommandDescriptor descriptor,
                                                   CommandAgent<?, ?, ?> agent,
                                                   ObjectSchema schema,
                                                   Set<String> commandFields,
                                                   Set<String> redactedFields,
                                                   List<String> emits) {
        Objects.requireNonNull(agent, "agent");
        return new CommandRegistration(descriptor, null, agent, schema, commandFields,
                redactedFields, emits, agent.isCreateCommand());
    }

    /**
     * 事件溯源注册项（无命令专用字段）。
     *
     * @param descriptor 命令描述
     * @param agent      命令代理
     * @param schema     对象 schema
     * @return 注册项
     */
    public static CommandRegistration eventSourced(CommandDescriptor descriptor,
                                                   CommandAgent<?, ?, ?> agent,
                                                   ObjectSchema schema) {
        return eventSourced(descriptor, agent, schema, Set.of(), Set.of(), List.of());
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
        this(descriptor, handler, null, schema, Set.of(), Set.of(), List.of(), false);
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
        this(descriptor, handler, null, schema, commandFields, Set.of(), List.of(), false);
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
        this(descriptor, handler, null, schema, commandFields, redactedFields, List.of(), false);
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
        this(descriptor, handler, null, schema, commandFields, redactedFields, emits, false);
    }

    /**
     * 完整双时间轴注册项。
     *
     * @param descriptor    命令描述
     * @param handler       处理器
     * @param schema        对象 schema
     * @param commandFields 命令专用字段
     * @param redactedFields 不得落命令记录的字段
     * @param emits         声明的事件类型
     * @param creates       是否为创建型命令
     */
    public CommandRegistration(CommandDescriptor descriptor, CommandHandler<?> handler,
                               ObjectSchema schema, Set<String> commandFields,
                               Set<String> redactedFields, List<String> emits, boolean creates) {
        this(descriptor, handler, null, schema, commandFields, redactedFields, emits, creates);
    }
}
