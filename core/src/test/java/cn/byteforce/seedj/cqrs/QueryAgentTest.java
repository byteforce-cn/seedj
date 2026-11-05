package cn.byteforce.seedj.cqrs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询模板：拦截链顺序、短路（缓存命中）、异常透传。
 *
 * <p>读侧没有锁与事务，唯一能保证"每次读取都经过统一处理"的地方就是这条链。
 */
class QueryAgentTest {

    @Test
    @DisplayName("没有拦截器时直接执行查询")
    void withoutInterceptorsDelegatesDirectly() {
        QueryAgent<String> agent = new QueryAgent<>();

        assertThat(agent.execute(() -> "value")).isEqualTo("value");
    }

    @Test
    @DisplayName("拦截器按列表顺序由外到内执行")
    void interceptorsRunFromOuterToInner() {
        List<String> trace = new ArrayList<>();
        QueryInterceptor<String> outer = invocation -> {
            trace.add("outer:enter");
            try {
                return invocation.proceed();
            } finally {
                trace.add("outer:exit");
            }
        };
        QueryInterceptor<String> inner = invocation -> {
            trace.add("inner:enter");
            try {
                return invocation.proceed();
            } finally {
                trace.add("inner:exit");
            }
        };
        QueryAgent<String> agent = new QueryAgent<>(List.of(outer, inner));

        assertThat(agent.execute(() -> {
            trace.add("query");
            return "value";
        })).isEqualTo("value");

        assertThat(trace).containsExactly(
                "outer:enter", "inner:enter", "query", "inner:exit", "outer:exit");
    }

    @Test
    @DisplayName("拦截器短路：查询不执行（缓存命中）")
    void interceptorCanServeFromCache() {
        AtomicInteger executions = new AtomicInteger();
        QueryAgent<Optional<String>> agent = new QueryAgent<>(
                List.of(invocation -> Optional.of("cached")));

        Optional<String> result = agent.execute(() -> {
            executions.incrementAndGet();
            return Optional.of("from-read-model");
        });

        assertThat(result).contains("cached");
        assertThat(executions).hasValue(0);
    }

    @Test
    @DisplayName("查询异常原样上抛")
    void propagatesQueryFailure() {
        QueryAgent<String> agent = new QueryAgent<>();

        assertThatThrownBy(() -> agent.execute(() -> {
            throw new IllegalStateException("read model unavailable");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read model unavailable");
    }

    @Test
    @DisplayName("拦截器可以改写结果")
    void interceptorCanRewriteResult() {
        QueryAgent<String> agent = new QueryAgent<>(
                List.of(invocation -> invocation.proceed().toUpperCase()));

        assertThat(agent.execute(() -> "value")).isEqualTo("VALUE");
    }
}
