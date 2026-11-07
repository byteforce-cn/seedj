package cn.byteforce.seedj.cqrs;

/**
 * 命令审计 seam：由 {@link CommandAgent} 在固定位置调用，业务代码不感知。
 *
 * <p>两个调用点的位置是契约的一部分：
 *
 * <ul>
 *   <li>{@link #accepted} 在命令事务内、保存与发布之后调用；抛异常 = 整个命令回滚（审计与状态变更同生共死）。
 *   <li>{@link #rejected} 在事务外调用，实现<b>必须</b>吞掉自身异常—— 它记录的是“尝试”，不得改变命令结果。
 * </ul>
 *
 * <p>实现由装配层提供（读注解、解析操作人、调用 {@code AuditAppender}）； 未启用审计时由装配层注入 {@code
 * DisabledCommandAudit}，不在契约里提供静默默认。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface CommandAudit {

    /**
     * 命令已受理（仍可能在提交时回滚，回滚则审计一并回滚）。
     *
     * @param command 命令
     * @param resourceId 已编码的聚合标识
     */
    default void accepted(Command<?> command, String resourceId) {}

    /**
     * 命令被业务拒绝（事务外调用；不得抛出）。
     *
     * @param command 命令
     * @param resourceId 已编码的聚合标识
     * @param error 拒绝原因
     */
    default void rejected(Command<?> command, String resourceId, RuntimeException error) {}

    /** 空实现，仅供测试与“显式关闭审计”的装配路径使用。 */
    static CommandAudit noop() {
        return new CommandAudit() {};
    }
}
