package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.cqrs.spi.TransactionRunner;
import cn.byteforce.seedj.domain.event.DomainEvent;
import cn.byteforce.seedj.eventbus.EventMessage;
import cn.byteforce.seedj.eventbus.EventPublisher;
import cn.byteforce.seedj.starter.im.protocol.ImCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 投递装配：provider 白名单与 coord 的前置依赖。 */
class ImPushAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ImPushAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class, ImEnabledMarker::new)
                    .withBean(ImCodec.class, ImCodec::new)
                    .withBean(ChannelRegistry.class, ChannelRegistry::new);

    private static EventPublisher publisher() {
        return new EventPublisher() {
            @Override
            public void publish(DomainEvent event) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void publish(EventMessage message) {}
        };
    }

    @Test
    @DisplayName("默认 local 只装配本节点投递")
    void localIsDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ImPushProvider.class);
                    assertThat(context.getBean(ImPushProvider.class))
                            .isInstanceOf(LocalPushProvider.class);
                });
    }

    @Test
    @DisplayName("未知 provider 启动失败，不静默退回本地")
    void unknownProviderFailsFast() {
        runner.withPropertyValues("seedj.im.push.provider=redis")
                .run(
                        context ->
                                assertThat(context)
                                        .getFailure()
                                        .hasMessageContaining(
                                                "unsupported seedj.im.push.provider"));
    }

    @Test
    @DisplayName("coord 有 EventPublisher 时装配协调投递与固定 node-id")
    void coordWithPublisher() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("publisher", publisher());
        ImProperties properties =
                new ImProperties(
                        true,
                        ImProperties.Store.MEMORY,
                        new ImProperties.Push("coord", "node-1"),
                        null,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null);

        ImPushProvider provider =
                new ImPushAutoConfiguration()
                        .imPushProvider(
                                new ImCodec(),
                                new ChannelRegistry(),
                                properties,
                                beans.getBeanProvider(EventPublisher.class),
                                beans.getBeanProvider(TransactionRunner.class));

        assertThat(provider).isInstanceOf(CoordPushProvider.class);
    }

    @Test
    @DisplayName("coord 缺少 EventPublisher 时启动失败并给出修复提示")
    void coordWithoutPublisherFailsFast() {
        runner.withPropertyValues("seedj.im.push.provider=coord")
                .run(
                        context ->
                                assertThat(context)
                                        .getFailure()
                                        .hasMessageContaining("requires an EventPublisher"));
    }
}
