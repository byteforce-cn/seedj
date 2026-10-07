package cn.byteforce.seedj.starter.coord.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoordMicrometerObservability} 指标映射测试。
 */
class CoordMicrometerObservabilityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CoordMicrometerObservability observability = new CoordMicrometerObservability(registry);

    @Test
    void recordsRpcCallsAsCounterAndTimer() {
        observability.recordRpcCall("registry.discover", 1_500_000L, true);
        observability.recordRpcCall("registry.discover", 2_000_000L, false);

        assertThat(registry.get(CoordMicrometerObservability.METRIC_RPC_CALLS)
                .tag("operation", "registry.discover").tag("outcome", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(CoordMicrometerObservability.METRIC_RPC_CALLS)
                .tag("operation", "registry.discover").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);

        var timer = registry.get(CoordMicrometerObservability.METRIC_RPC_LATENCY)
                .tag("operation", "registry.discover").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isEqualTo(3_500_000.0);
    }

    @Test
    void recordsWatchEvents() {
        observability.recordWatchEvent("config.watch");
        observability.recordWatchEvent("config.watch");

        assertThat(registry.get(CoordMicrometerObservability.METRIC_WATCH_EVENTS)
                .tag("operation", "config.watch").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void tracksConnectionStateAsGaugeFamily() {
        observability.recordConnectionStateChange("CONNECTED", "handshake ok");
        assertThat(gaugeValue("CONNECTED")).isEqualTo(1.0);

        observability.recordConnectionStateChange("DISCONNECTED", "channel closed");
        assertThat(gaugeValue("DISCONNECTED")).isEqualTo(1.0);
        assertThat(gaugeValue("CONNECTED")).isEqualTo(0.0);
    }

    @Test
    void blankValuesFallBackToUnknown() {
        observability.recordRpcCall(null, 1L, true);
        observability.recordConnectionStateChange(" ", null);

        assertThat(registry.get(CoordMicrometerObservability.METRIC_RPC_CALLS)
                .tag("operation", "unknown").counter().count()).isEqualTo(1.0);
        assertThat(gaugeValue("unknown")).isEqualTo(1.0);
    }

    private double gaugeValue(String state) {
        Gauge gauge = Search.in(registry)
                .name(CoordMicrometerObservability.METRIC_CONNECTION_STATE)
                .tag("state", state)
                .gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
