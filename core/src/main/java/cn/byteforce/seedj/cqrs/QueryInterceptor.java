package cn.byteforce.seedj.cqrs;

/**
 * 查询拦截钩子：缓存、鉴权、多租户过滤、慢查询指标。
 *
 * <p>查询不参与锁与事务，因此拦截器是唯一能保证"每次读取都经过统一处理"的地方。 短路（不调用 {@link QueryInvocation#proceed()}）即命中缓存或拒绝访问。
 *
 * @param <R> 读取结果类型
 * @author ByteForce
 * @since 1.0.0
 */
@FunctionalInterface
public interface QueryInterceptor<R> {

    R around(QueryInvocation<R> invocation);
}
