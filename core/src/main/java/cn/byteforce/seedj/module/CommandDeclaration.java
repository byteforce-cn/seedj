package cn.byteforce.seedj.module;

import cn.byteforce.seedj.cqrs.CommandAgent;
import cn.byteforce.seedj.cqrs.CommandHandler;

import java.util.List;
import java.util.Set;

/**
 * 一条命令声明（注册表的只读快照）。
 *
 * @param objectType  对象类型
 * @param type        命令类型
 * @param payloadType 载荷 record
 * @param permission  命令权限
 * @param handler     双时间轴处理器；事件溯源命令为 {@code null}
 * @param agent       事件溯源命令代理；双时间轴命令为 {@code null}
 * @param creates     是否为创建型命令
 * @param emits       声明的事件类型
 * @param commandFields  命令专用、不在 schema 中的字段
 * @param redactedFields 命令专用且不得落命令记录的字段
 * @author ByteForce
 * @since 1.0.0
 */
public record CommandDeclaration(String objectType, String type, Class<?> payloadType,
                                 String permission, CommandHandler<?> handler,
                                 CommandAgent<?, ?, ?> agent, boolean creates,
                                 List<String> emits, Set<String> commandFields,
                                 Set<String> redactedFields) {

    public CommandDeclaration {
        emits = emits == null ? List.of() : List.copyOf(emits);
        commandFields = commandFields == null ? Set.of() : Set.copyOf(commandFields);
        redactedFields = redactedFields == null ? Set.of() : Set.copyOf(redactedFields);
    }

    /**
     * 兼容既有声明：双时间轴命令。
     *
     * @param objectType  对象类型
     * @param type        命令类型
     * @param payloadType 载荷 record
     * @param permission  命令权限
     * @param handler     处理器
     * @param creates     是否为创建型命令
     * @param emits       声明的事件类型
     * @param commandFields  命令专用字段
     * @param redactedFields 不得落命令记录的字段
     */
    public CommandDeclaration(String objectType, String type, Class<?> payloadType,
                              String permission, CommandHandler<?> handler, boolean creates,
                              List<String> emits, Set<String> commandFields,
                              Set<String> redactedFields) {
        this(objectType, type, payloadType, permission, handler, null, creates, emits,
                commandFields, redactedFields);
    }
}
