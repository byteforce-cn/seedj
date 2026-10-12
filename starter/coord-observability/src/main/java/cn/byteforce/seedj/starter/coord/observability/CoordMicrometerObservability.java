package cn.byteforce.seedj.starter.coord.observability;

import cn.byteforce.coord.sdk.spi.ObservabilityProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SDK {@link ObservabilityProvider} → Micrometer 指标桥。
 *
 * <p>指标（前缀 {@code coord.}）：</p>
 * <ul>
 *   <li>{@code coord.rpc.calls}（counter，tags: {@code operation}, {@code outcome=success|error}）；</li>
 *   <li>{@code coord.rpc.latency}（timer，tag: {@code operation}；SDK 上报纳秒）；</li>
 *   <li>{@code coord.watch.events}（counter，tag: {@code operation}）；</li>
 *   <li>{@code coord.connection.state}（gauge 族，tag: {@code state}；当前状态为 1、其余为 0）。</li>
 * </ul>
 *
 * <p>SDK 0.3.0 实证：运行期实际触发 {@code recordRpcCall}；
 * {@code recordWatchEvent} / {@code recordConnectionStateChange} 为 SPI 预留面
 * （上报实现就绪，SDK 触发后自动生效）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class CoordMicrometerObservability implements ObservabilityProvider {

    /** RPC 调用计数器名。 */
    public static final String METRIC_RPC_CALLS = "coord.rpc.calls";

    /** RPC 时延计时器名。 */
    public static final String METRIC_RPC_LATENCY = "coord.rpc.latency";

    /** Watch 事件计数器名。 */
    public static final String METRIC_WATCH_EVENTS = "coord.watch.events";

    /** 连接状态 gauge 名。 */
    public static final String METRIC_CONNECTION_STATE = "coord.connection.state";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> connectionStates = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentState = new AtomicReference<>();

    /**
     * @param meterRegistry Micrometer 注册表
     */
    public CoordMicrometerObservability(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordRpcCall(String operation, long latencyNanos, boolean success) {
        String outcome = success ? "success" : "error";
        meterRegistry.counter(METRIC_RPC_CALLS, "operation", safe(operation), "outcome", outcome).increment();
        Timer.builder(METRIC_RPC_LATENCY)
                .description("coord RPC 时延")
                .tag("operation", safe(operation))
                .register(meterRegistry)
                .record(Duration.ofNanos(latencyNanos));
    }

    @Override
    public void recordWatchEvent(String operation) {
        meterRegistry.counter(METRIC_WATCH_EVENTS, "operation", safe(operation)).increment();
    }

    @Override
    public void recordConnectionStateChange(String state, String detail) {
        String newState = safe(state);
        String previous = currentState.getAndSet(newState);
        gaugeFor(newState).set(1);
        if (previous != null && !previous.equals(newState)) {
            gaugeFor(previous).set(0);
        }
    }

    private AtomicInteger gaugeFor(String state) {
        return connectionStates.computeIfAbsent(state, key -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder(METRIC_CONNECTION_STATE, value, AtomicInteger::doubleValue)
                    .description("coord Agent 连接状态（当前为 1）")
                    .tag("state", key)
                    .register(meterRegistry);
            return value;
        });
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
