package cn.byteforce.seedj.eventbus.wiring;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventConsumerStore;
import cn.byteforce.seedj.eventbus.spi.EventPayloadSerializer;
import cn.byteforce.seedj.outbox.OutboxWriter;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.observability.wiring.TracePropagation;
import cn.byteforce.seedj.outbox.spi.OutboxSink;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

/**
 * EventBus 装配（全部 Spring 组装集中在 starter）。
 *
 * <p>本类只做装配，不提供任何 transport / consumer store 实现：
 * <ul>
 *   <li>队列：{@code seedj-infra-local}（file / memory）；</li>
 *   <li>outbox：{@code seedj-jdbc-postgresql} + 存储实现（如 seedj-jdbc-postgresql）；</li>
 *   <li>两者都没有：{@code EventPublisher} 仍然存在，但 publish 会抛
 *       {@code NO_TRANSPORT}，而不是让应用启动失败。</li>
 * </ul>
 *
 * <p>eventbus 只提供 outbox 的投递适配器 {@link EventBusOutboxSink}；
 * relay、存储与租约全部归 outbox 能力。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.eventbus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EventBusProperties.class)
public class EventBusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher eventPublisher(ObjectProvider<OutboxStore> outboxStore,
                                         ObjectProvider<OutboxWriter> outboxWriter,
                                         ObjectProvider<EventBusTransport> transport,
                                         ObjectProvider<EventPayloadSerializer> serializer,
                                         IdGenerator<String> idGenerator,
                                         EventBusMetrics metrics,
                                         TracePropagation tracing) {
        return new DefaultEventPublisher(outboxStore, outboxWriter, transport, serializer,
                idGenerator, metrics, tracing);
    }

    /**
     * 让 eventbus 成为 outbox 的投递目标；用户自定义 {@link OutboxSink} 时退让。
     */
    @Bean
    @ConditionalOnMissingBean(OutboxSink.class)
    public OutboxSink eventBusOutboxSink(ObjectProvider<EventBusTransport> transport) {
        return new EventBusOutboxSink(transport);
    }

    /**
     * 启动期确保订阅/配置声明的 topic 存在。
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventBusTopicCapability")
    public InfrastructureCapability eventBusTopicCapability(
            ObjectProvider<EventBusTransport> transport,
            ObjectProvider<EventSubscriber<?>> subscribers,
            EventBusProperties properties) {
        return new EventTopicCapability(transport, subscribers, properties);
    }

    /**
     * 消费容器：发现 EventSubscriber bean，按消费者组拉取并处理事件。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @DependsOn("infrastructureInitializer")
    public EventConsumerContainer eventConsumerContainer(
            ObjectProvider<EventConsumerStore> stores,
            ObjectProvider<EventSubscriber<?>> subscribers,
            ObjectProvider<EventPayloadSerializer> serializers,
            EventBusProperties properties,
            EventBusMetrics metrics,
            TracePropagation tracing,
            Environment environment) {
        return new EventConsumerContainer(stores, subscribers, serializers, properties, metrics,
                tracing, environment);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    static class NoMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        EventBusMetrics eventBusMetrics() {
            return EventBusMetrics.noop();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        EventBusMetrics eventBusMetrics(ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
            io.micrometer.core.instrument.MeterRegistry resolved = registry.getIfAvailable();
            return resolved == null ? EventBusMetrics.noop() : new MicrometerEventBusMetrics(resolved);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
    static class JacksonSerializerConfiguration {
        @Bean
        @ConditionalOnMissingBean
        EventPayloadSerializer jacksonEventPayloadSerializer(
                ObjectProvider<tools.jackson.databind.ObjectMapper> mapper) {
            tools.jackson.databind.ObjectMapper resolved = mapper.getIfAvailable(
                    () -> tools.jackson.databind.json.JsonMapper.builder().build());
            return new EventPayloadSerializer() {
                @Override
                public byte[] serialize(Object payload) {
                    try {
                        return resolved.writeValueAsBytes(payload);
                    } catch (Exception e) {
                        throw new cn.byteforce.seedj.eventbus.EventBusException(
                                cn.byteforce.seedj.eventbus.EventBusErrorCode.SERIALIZATION_FAILED,
                                "cannot serialize event payload", e);
                    }
                }

                @Override
                public <T> T deserialize(byte[] payload, Class<T> type) {
                    try {
                        return resolved.readValue(payload, type);
                    } catch (Exception e) {
                        throw new cn.byteforce.seedj.eventbus.EventBusException(
                                cn.byteforce.seedj.eventbus.EventBusErrorCode.SERIALIZATION_FAILED,
                                "cannot deserialize event payload as " + type.getName(), e);
                    }
                }
            };
        }
    }
}
