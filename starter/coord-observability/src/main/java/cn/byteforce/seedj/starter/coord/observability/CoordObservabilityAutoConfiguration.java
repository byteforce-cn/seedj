package cn.byteforce.seedj.starter.coord.observability;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.spi.ObservabilityProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Coord 可观测性桥自动配置（starter-coord-observability，Phase 3）。
 *
 * <p>前置：classpath 存在 Micrometer（如使用方引入 Actuator）且存在 {@link MeterRegistry}
 * （惰性探测：无 Registry 时返回 null Bean 优雅回退，不阻塞应用启动——
 * 与 micrometer-core、无 meter 设施的组合兼容）。
 * 桥以 {@link ObservabilityProvider} 形态注册，由 {@code CoordAutoConfiguration}
 * 注入到 SDK 客户端（AgentRpcClient 实测调用 {@code recordRpcCall}）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration
@ConditionalOnClass({CoordClient.class, MeterRegistry.class})
@AutoConfigureAfter(name = "cn.byteforce.seedj.starter.coord.CoordAutoConfiguration")
@ConditionalOnProperty(
        prefix = "byteforce.coord.observability",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(CoordObservabilityProperties.class)
public class CoordObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CoordObservabilityAutoConfiguration.class);

    /**
     * Micrometer 指标桥（使用方自定义 {@link ObservabilityProvider} 时回退）。
     *
     * <p>Bean 创建期惰性探测 {@link MeterRegistry}：不存在时返回 null（NullBean），
     * 桥整体不成型且不影响启动；使用方的 meter 设施（如 Actuator）就绪后自动生效。</p>
     */
    @Bean
    @ConditionalOnMissingBean(ObservabilityProvider.class)
    public CoordMicrometerObservability coordMicrometerObservability(
            ObjectProvider<MeterRegistry> meterRegistries) {
        MeterRegistry meterRegistry = meterRegistries.getIfAvailable();
        if (meterRegistry == null) {
            log.info("classpath 存在 Micrometer 但无 MeterRegistry Bean，coord 指标桥未启用");
            return null;
        }
        return new CoordMicrometerObservability(meterRegistry);
    }
}
