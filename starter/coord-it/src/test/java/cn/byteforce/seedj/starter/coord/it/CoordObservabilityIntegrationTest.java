package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.config.ConfigClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可观测性桥集成验证（Phase 3）：真实 RPC → SDK {@code recordRpcCall} →
 * Micrometer {@code coord.rpc.calls} 计数增长（SDK 0.2.0 实测唯一触发面）。
 */
@SpringBootTest(properties = {
        "spring.application.name=it-observability"
})
class CoordObservabilityIntegrationTest extends AbstractCoordIntegrationTest {

    @Autowired
    private ConfigClient configClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void rpcCallsAreBridgedIntoMicrometer() {
        // 任意真实 RPC（不存在 key → Optional.empty，不抛错）
        configClient.getString(uniqueName("it.obs.key"));

        awaitUntil("RPC 指标增长", Duration.ofSeconds(15), () -> rpcCallCount() > 0);
    }

    private double rpcCallCount() {
        try {
            return meterRegistry.get("coord.rpc.calls").meters().stream()
                    .filter(Counter.class::isInstance)
                    .mapToDouble(meter -> ((Counter) meter).count())
                    .sum();
        } catch (MeterNotFoundException notYet) {
            return 0;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MeterConfiguration {

        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
