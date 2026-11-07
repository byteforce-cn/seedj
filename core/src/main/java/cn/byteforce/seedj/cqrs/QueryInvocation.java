package cn.byteforce.seedj.cqrs;

/**
 * 一次查询执行（拦截器视角）。
 *
 * @param <R> 读取结果类型
 * @author ByteForce
 * @since 1.0.0
 */
public interface QueryInvocation<R> {

    /** 正在执行的查询。 */
    Query<R> query();

    /** 继续执行（可能命中缓存并短路）。 */
    R proceed();
}
