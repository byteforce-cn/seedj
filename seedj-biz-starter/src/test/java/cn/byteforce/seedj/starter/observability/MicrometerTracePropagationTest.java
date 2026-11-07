package cn.byteforce.seedj.starter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerTracePropagationTest {

    private final SimpleTracer tracer = new SimpleTracer();

    private final MicrometerTracePropagation propagation =
            new MicrometerTracePropagation(tracer, new MapPropagator(tracer));

    @Test
    @DisplayName("有当前 span 时注入 traceparent，未覆盖已有 header")
    void injectsTraceparent() {
        Span span = tracer.nextSpan().name("publish").start();
        Map<String, String> injected;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            injected = propagation.inject(Map.of("tenant", "t-1"));
        }

        assertThat(injected).containsEntry("tenant", "t-1");
        assertThat(injected.get("traceparent"))
                .startsWith("00-" + span.context().traceId() + "-" + span.context().spanId() + "-");
    }

    @Test
    @DisplayName("已有 traceparent 时不覆盖（逃生舱/跨语言生产者）")
    void doesNotOverwriteGivenTraceparent() {
        Map<String, String> existing = Map.of("traceparent", "00-keep-me");

        assertThat(propagation.inject(existing)).isSameAs(existing);
    }

    @Test
    @DisplayName("消费端恢复 remote parent，span 是发布端的子节点")
    void extractRestoresRemoteParent() {
        Span published = tracer.nextSpan().name("publish").start();
        Map<String, String> headers;
        try (Tracer.SpanInScope ignored = tracer.withSpan(published)) {
            headers = propagation.inject(Map.of());
        }
        published.end();

        try (TracePropagation.Scope scope = propagation.extract(headers)) {
            TraceContext consumed = tracer.currentSpan().context();
            assertThat(consumed.traceId()).isEqualTo(published.context().traceId());
            assertThat(consumed.parentId()).isEqualTo(published.context().spanId());
        }
    }

    /** 只实现 traceparent 的最小 propagator，用来验证 seam 与 span 关系， 不依赖具体的 bridge（Brave / OTel）。 */
    static final class MapPropagator implements Propagator {

        private final Tracer tracer;

        MapPropagator(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public List<String> fields() {
            return List.of("traceparent");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            setter.set(
                    carrier,
                    "traceparent",
                    "00-" + context.traceId() + "-" + context.spanId() + "-01");
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            String traceparent = getter.get(carrier, "traceparent");
            if (traceparent == null) {
                return Span.Builder.NOOP;
            }
            String[] parts = traceparent.split("-");
            if (parts.length < 4) {
                return Span.Builder.NOOP;
            }
            TraceContext parent =
                    tracer.traceContextBuilder()
                            .traceId(parts[1])
                            .spanId(parts[2])
                            .sampled("01".equals(parts[3]))
                            .build();
            return tracer.spanBuilder().setParent(parent);
        }
    }
}
