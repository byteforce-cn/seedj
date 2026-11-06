package cn.byteforce.seedj.infra.local.eventbus;

import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.id.IdGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 本地队列装配：{@code seedj.eventbus.transport=file|memory}。
 *
 * <p>默认 {@code file}：磁盘队列，同机多服务共享目录即可统一可见。
 * 只有显式选择 {@code memory} 时才使用进程内队列。
 *
 * <p>Bean 方法返回具体类型，Spring 才能同时识别 {@link EventBusTransport} 与
 * {@link cn.byteforce.seedj.eventbus.spi.EventConsumerStore}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.eventbus", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EventTransportProperties.class)
public class EventTransportAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBusTransport.class)
    @ConditionalOnProperty(prefix = "seedj.eventbus", name = "transport",
            havingValue = "file", matchIfMissing = true)
    public FileEventBusTransport fileEventBusTransport(EventTransportProperties properties,
                                                       IdGenerator<String> idGenerator) {
        return new FileEventBusTransport(properties.getFile().getBaseDir(), idGenerator);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventBusTransport.class)
    @ConditionalOnProperty(prefix = "seedj.eventbus", name = "transport", havingValue = "memory")
    public MemoryEventBusTransport memoryEventBusTransport(EventTransportProperties properties,
                                                          IdGenerator<String> idGenerator) {
        return new MemoryEventBusTransport(properties.getMemory().getCapacity(), idGenerator);
    }
}
