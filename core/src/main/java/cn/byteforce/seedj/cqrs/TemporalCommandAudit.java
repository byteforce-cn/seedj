package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.event.CommandEvent;

import java.util.List;

/**
 * 临时命令审计 seam：由 {@link CommandRuntime} 在命令事务内、命令终态写完后调用。
 *
 * <p>与事件溯源路径的 {@link CommandAudit} 并列：本 seam 不读注解，每条受理命令
 * 都必须审计。抛异常 = 整个命令事务回滚（审计与对象行/命令终态同生共死）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface TemporalCommandAudit {

    /**
     * 受理命令已写入对象与事件。
     *
     * @param record     已完成的命令记录
     * @param objectType 对象类型（来自模块声明）
     * @param events     本次命令产生的事件（顺序即写入顺序）
     */
    void accepted(CommandRecord record, String objectType, List<CommandEvent> events);

    /**
     * 空实现，仅供测试与关闭审计的装配路径使用。
     *
     * @return 不写任何内容的 seam
     */
    static TemporalCommandAudit noop() {
        return (record, objectType, events) -> {
        };
    }
}
