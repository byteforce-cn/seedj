package cn.byteforce.seedj.observability.wiring;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.HashMap;
import java.util.Map;

/**
 * micrometer-tracing 实现：W3C traceparent 注入 / 提取。
 *
 * <p>消费端用提取出的 {@link Span.Builder} 建子 span（父上下文由 propagator 还原），
 * 采样位随 traceparent 继承；MDC 由 tracing bridge 的 correlation 装饰器在作用域内
 * 自动填充，本类不碰 MDC。</p>
 */
public final class MicrometerTracePropagation implements TracePropagation {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;

    private final Propagator propagator;

    public MicrometerTracePropagation(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> inject(Map<String, String> headers) {
        if (headers.containsKey(TRACEPARENT)) {
            return headers;
        }
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return headers;
        }
        Map<String, String> carrier = new HashMap<>(headers);
        propagator.inject(context, carrier, Map::put);
        return Map.copyOf(carrier);
    }

    @Override
    public Scope extract(Map<String, String> headers) {
        if (!headers.containsKey(TRACEPARENT)) {
            return () -> {
            };
        }
        Span.Builder builder = propagator.extract(headers, Map::get);
        Span span = builder.name("event.consume").start();
        Tracer.SpanInScope spanInScope = tracer.withSpan(span);
        return () -> {
            spanInScope.close();
            span.end();
        };
    }
}
