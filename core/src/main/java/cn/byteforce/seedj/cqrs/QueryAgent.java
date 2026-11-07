package cn.byteforce.seedj.cqrs;

import java.util.List;
import java.util.Objects;

/**
 * 读模型查询代理：把查询包进拦截链。
 *
 * <p>与 {@link CommandAgent} 的差别是刻意的——读侧没有"加锁/事务/重试"，只有 "拦截 + 执行"。所以本类没有模板方法，只有一个链执行器；顺序保证是写侧的事。
 *
 * <p>一个读模型一个实例（{@code QueryAgent<Optional<EmployeeView>>}），拦截器按读模型 分别配置：缓存键、鉴权粒度通常不同，混用一个 agent
 * 只会让缓存互相污染。
 *
 * @param <R> 读取结果类型
 * @author ByteForce
 * @since 1.0.0
 */
public final class QueryAgent<R> {

    private final List<QueryInterceptor<R>> interceptors;

    public QueryAgent() {
        this(List.of());
    }

    public QueryAgent(List<QueryInterceptor<R>> interceptors) {
        this.interceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
    }

    /** 执行查询。列表靠前的拦截器在外层。 */
    public R execute(Query<R> query) {
        Objects.requireNonNull(query, "query");

        QueryInvocation<R> invocation =
                new QueryInvocation<>() {
                    @Override
                    public Query<R> query() {
                        return query;
                    }

                    @Override
                    public R proceed() {
                        return query.execute();
                    }
                };

        for (int i = interceptors.size() - 1; i >= 0; i--) {
            QueryInterceptor<R> interceptor = interceptors.get(i);
            QueryInvocation<R> inner = invocation;
            invocation =
                    new QueryInvocation<>() {
                        @Override
                        public Query<R> query() {
                            return inner.query();
                        }

                        @Override
                        public R proceed() {
                            return interceptor.around(inner);
                        }
                    };
        }
        return invocation.proceed();
    }
}
