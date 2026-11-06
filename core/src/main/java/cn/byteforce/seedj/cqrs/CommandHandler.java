package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.event.CommandEvent;

import java.util.List;
import java.util.UUID;

/**
 * 显式命令处理器：业务规则的唯一落点。
 *
 * @param <C> 命令载荷类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface CommandHandler<C> {

    /**
     * 目标对象 ID；必须是纯函数，且只在幂等检查之后调用。
     *
     * @param command 命令载荷
     * @return 目标 ID
     */
    UUID targetId(C command);

    /**
     * 在锁内、事务内决定追加请求。
     *
     * @param command 命令载荷
     * @param ctx     执行上下文
     * @return 追加请求
     */
    AppendRequest decide(C command, CommandContext ctx);

    /**
     * 本次命令产生的事件。
     *
     * @param command 命令载荷
     * @param result  追加结果
     * @param ctx     执行上下文
     * @return 事件列表
     */
    default List<CommandEvent> events(C command, AppendResult result, CommandContext ctx) {
        return List.of();
    }
}
