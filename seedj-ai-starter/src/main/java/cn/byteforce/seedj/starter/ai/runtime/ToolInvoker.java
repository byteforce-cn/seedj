package cn.byteforce.seedj.starter.ai.runtime;

import java.util.UUID;

/**
 * 工具端口：只读查询与命令执行。
 *
 * <p><b>token 只走带外</b>：{@link #execute(Call, String, UUID)} 的 token 由实现放进传输层元数据（HTTP 头 {@code
 * x-agent-decision}），绝不进请求体、日志或异常消息（spec §5.1 关键 3、§7.2）。{@code decisionId} 恒等于 命令的幂等键。
 *
 * <p>{@link #read(Call)} 没有 token：只读路径不签发 DecisionToken（spec §5.1 只读路径）。
 *
 * <p><b>T0 的循环不执行查询</b>：{@link BoundedLoop} 只对 {@code EXECUTE_TOOL} 调 {@link #execute}；{@code read}
 * 供宿主直接驱动用（T0 pilot 不走查询），因此它不参与循环的观测与上限计数。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public interface ToolInvoker {

    /**
     * 执行一次只读查询。
     *
     * @param call 查询类型与过滤条件
     * @return 查询结果
     */
    ToolResult read(Call call);

    /**
     * 执行一条命令。
     *
     * @param call 命令类型、载荷、目标与期望版本
     * @param token 决策面签发的 DecisionToken
     * @param decisionId 决策标识，同时是命令的幂等键
     * @return 命令结果
     */
    ToolResult execute(Call call, String token, UUID decisionId);
}
