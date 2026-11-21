package cn.byteforce.seedj.ai;

import java.util.Objects;

/**
 * 一个可被 agent 调用的命令：命令类型与其动作分类的绑定。
 *
 * <p>动作分类由命令注册方声明，不从调用方读取——否则调用方可以自己挑最宽松的一档绕开审批。
 *
 * @param commandType 命令类型（{@code CommandCatalog} 的注册类型）
 * @param actionClass 动作分类
 * @author ByteForce
 * @since 1.0.0
 */
public record AgentToolSpec(String commandType, ActionClass actionClass) {

    /**
     * 校验命令类型与动作分类。
     *
     * @throws NullPointerException 任一组件为 {@code null}
     * @throws IllegalArgumentException {@code commandType} 为空白
     */
    public AgentToolSpec {
        Objects.requireNonNull(commandType, "commandType");
        if (commandType.isBlank()) {
            throw new IllegalArgumentException("commandType must not be blank");
        }
        Objects.requireNonNull(actionClass, "actionClass");
    }
}
