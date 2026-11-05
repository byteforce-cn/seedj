package cn.byteforce.seedj.cqrs;

/**
 * 命令拦截钩子：审计、鉴权、幂等去重、指标、多租户过滤。
 *
 * <p>拦截器在<b>锁与事务之外</b>执行，因此：
 * <ul>
 *   <li>可以短路（不调用 {@link CommandInvocation#proceed()}）；</li>
 *   <li>不能依赖"已经拿到锁"或"事务已开启"——需要与事件流同生共死的写入请放进
 *       聚合或仓储，不要放进拦截器；</li>
 *   <li>执行过程中抛出的 <b>{@link cn.byteforce.seedj.core.InfrastructureException}
 *       子类</b>会原样上抛；其他运行时异常同样上抛，不会触发重试。</li>
 * </ul>
 *
 * <p>顺序：列表靠前的拦截器在外层（先进入、后返回）。
 *
 * @param <ID> 聚合标识类型
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface CommandInterceptor<ID> {

    ID around(CommandInvocation<ID> invocation);
}
