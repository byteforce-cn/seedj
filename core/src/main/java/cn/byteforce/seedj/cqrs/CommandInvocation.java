package cn.byteforce.seedj.cqrs;

/**
 * 一次命令执行（拦截器视角）。
 *
 * <p>{@link #proceed()} 继续执行后续拦截器与命令本身；不调用它就是短路。
 * 在统一命令信封下，幂等与非法请求已在进入 agent 之前处理；agent 内的短路必须
 * 已经留下终态命令记录，否则失败关闭（{@code IllegalStateException}）。
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface CommandInvocation<ID> {

    /**
     * 正在执行的命令。
     */
    Command<ID> command();

    /**
     * 目标聚合标识（已由 {@code command.aggregateId()} 取好，避免每次判空）。
     */
    ID aggregateId();

    /**
     * 继续执行。返回命令结果（聚合标识）。
     */
    ID proceed();
}
