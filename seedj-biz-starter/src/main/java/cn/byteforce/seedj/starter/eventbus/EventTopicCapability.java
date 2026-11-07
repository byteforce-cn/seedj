package cn.byteforce.seedj.starter.eventbus;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.core.InfrastructureErrorCode;
import cn.byteforce.seedj.core.InfrastructureException;
import cn.byteforce.seedj.eventbus.EventSubscriber;
import cn.byteforce.seedj.eventbus.EventSubscription;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 保证订阅/配置声明的队列 topic 在启动时一定存在。
 *
 * <p>topic 来源：{@code seedj.eventbus.topics} + 所有 {@link EventSubscriber} 的订阅。 文件队列展开为目录，内存队列登记
 * topic，真实 MQ 适配器可在这里创建 topic。
 */
public class EventTopicCapability implements InfrastructureCapability {

    public static final String NAME = "eventbus-topics";

    private final ObjectProvider<EventBusTransport> transport;
    private final ObjectProvider<EventSubscriber<?>> subscribers;
    private final EventBusProperties properties;

    public EventTopicCapability(
            ObjectProvider<EventBusTransport> transport,
            ObjectProvider<EventSubscriber<?>> subscribers,
            EventBusProperties properties) {
        this.transport = transport;
        this.subscribers = subscribers;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean isReady() {
        EventBusTransport busTransport = transport.getIfAvailable();
        if (busTransport == null) {
            return true;
        }
        return topics().stream().allMatch(busTransport::topicExists);
    }

    @Override
    public void initialize() {
        EventBusTransport busTransport = transport.getIfAvailable();
        if (busTransport == null) {
            return;
        }
        for (String topic : topics()) {
            busTransport.ensureTopic(topic);
        }
    }

    @Override
    public void validate() {
        if (!isReady()) {
            throw new InfrastructureException(
                    InfrastructureErrorCode.NOT_READY,
                    "eventbus topics are not ready after initialization: " + topics());
        }
    }

    /** 配置 topic + 订阅 topic 的并集。 */
    Set<String> topics() {
        Set<String> topics = new LinkedHashSet<>(properties.getTopics());
        subscribers
                .orderedStream()
                .forEach(
                        subscriber -> {
                            EventSubscription subscription = subscriber.subscription();
                            topics.addAll(subscription.topics());
                        });
        return topics;
    }
}
