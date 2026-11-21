package cn.byteforce.seedj.starter.im;

import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 投递装配：{@code seedj.im.push.provider=local|coord}。
 *
 * <p>{@code local} 只投本节点连接；{@code coord} 额外把投递意向写入 outbox，由 EventBus（host 选择 {@code
 * seedj.eventbus.transport=coord}）广播给其它节点。协调依赖是可选的：本模块只依赖 {@link EventPublisher} 接口，不依赖 coord SDK。
 *
 * @author ByteForce
 * @since 1.0.0
 */
@AutoConfiguration(after = ImServerAutoConfiguration.class)
@ConditionalOnBean(ImEnabledMarker.class)
@ConditionalOnProperty(
        prefix = "seedj.im",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ImProperties.class)
public class ImPushAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ImPushAutoConfiguration.class);

    /**
     * 投递实现。未知的 provider 是启动失败：静默退回本地会让多节点部署「看起来在跑、只有一台收到」。
     *
     * @param codec 编解码
     * @param registry 本节点连接注册表
     * @param properties IM 配置
     * @param publishers 事件发布端口；coord 模式必需
     * @param transactions 事务边界；没有时直通
     * @return 投递端口
     */
    @Bean
    @ConditionalOnMissingBean(ImPushProvider.class)
    ImPushProvider imPushProvider(
            ImCodec codec,
            ChannelRegistry registry,
            ImProperties properties,
            ObjectProvider<EventPublisher> publishers,
            ObjectProvider<TransactionRunner> transactions) {
        LocalPushProvider local = new LocalPushProvider(codec, registry);
        String provider = properties.push().provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "local" -> local;
            case "coord" -> coordinator(local, properties, publishers, transactions);
            default ->
                    throw new IllegalStateException(
                            "unsupported seedj.im.push.provider: "
                                    + properties.push().provider()
                                    + " (expected local|coord)");
        };
    }

    /**
     * 跨节点投递的消费端；只在 coord 模式下注册。
     *
     * @param push 已经装配好的投递端口
     * @param messaging 消息服务：把消息投影成投递视图
     * @param messages 消息存储：按 message_id 回读正文
     * @return 订阅器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "seedj.im.push", name = "provider", havingValue = "coord")
    ImDeliverySubscriber imDeliverySubscriber(
            ImPushProvider push, ImMessagingService messaging, ImMessageStore messages) {
        if (!(push instanceof CoordPushProvider coord)) {
            throw new IllegalStateException(
                    "seedj.im.push.provider=coord requires a CoordPushProvider; got "
                            + push.getClass().getName());
        }
        return coord.subscriber(messaging, messages);
    }

    private ImPushProvider coordinator(
            LocalPushProvider local,
            ImProperties properties,
            ObjectProvider<EventPublisher> publishers,
            ObjectProvider<TransactionRunner> transactions) {
        EventPublisher publisher = publishers.getIfAvailable();
        if (publisher == null) {
            throw new IllegalStateException(
                    "seedj.im.push.provider=coord requires an EventPublisher; enable"
                            + " seedj.eventbus or set seedj.im.push.provider=local");
        }
        if (properties.store() == ImProperties.Store.MEMORY) {
            log.warn(
                    "seedj.im.push.provider=coord with seedj.im.store=memory: other nodes cannot"
                            + " read the message body, only the originating node can deliver");
        }
        String nodeId =
                properties.push().nodeId() == null
                        ? UUID.randomUUID().toString()
                        : properties.push().nodeId();
        ImCoordPush coord =
                new ImCoordPush(
                        nodeId,
                        publisher,
                        transactions.getIfAvailable(TransactionRunner::direct),
                        local);
        return new CoordPushProvider(local, coord);
    }
}
