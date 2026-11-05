package cn.byteforce.seedj.observability.wiring;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 追踪装配。宿主应用自带 micrometer-tracing bridge 时使用真实传播，
 * 否则退化为 no-op——不会因为缺依赖而启动失败。
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    static class MicrometerTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TracePropagation tracePropagation(ObjectProvider<Tracer> tracer,
                                          ObjectProvider<Propagator> propagator) {
            Tracer resolvedTracer = tracer.getIfAvailable();
            Propagator resolvedPropagator = propagator.getIfAvailable();
            return resolvedTracer == null || resolvedPropagator == null
                    ? TracePropagation.noop()
                    : new MicrometerTracePropagation(resolvedTracer, resolvedPropagator);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.tracing.Tracer")
    static class NoTracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TracePropagation tracePropagation() {
            return TracePropagation.noop();
        }
    }
}
