package cn.byteforce.seedj.starter.observability;

import java.util.Map;

/**
 * 追踪上下文传播 seam：有 micrometer-tracing 时由装配层注入真实实现，否则 no-op。
 *
 * <p>只传 W3C {@code traceparent} / {@code tracestate}：headers 会落库并对消费者可见， baggage（租户、用户）不得进入。
 */
public interface TracePropagation {

    /** 把当前上下文写入 headers；已有 {@code traceparent} 时原样返回（逃生舱/跨语言生产者）。 */
    Map<String, String> inject(Map<String, String> headers);

    /** 从 headers 恢复上下文；{@code close()} 归还作用域并结束 span。 没有 {@code traceparent} 时返回空作用域。 */
    Scope extract(Map<String, String> headers);

    interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    static TracePropagation noop() {
        return new TracePropagation() {
            @Override
            public Map<String, String> inject(Map<String, String> headers) {
                return headers;
            }

            @Override
            public Scope extract(Map<String, String> headers) {
                return () -> {};
            }
        };
    }
}
