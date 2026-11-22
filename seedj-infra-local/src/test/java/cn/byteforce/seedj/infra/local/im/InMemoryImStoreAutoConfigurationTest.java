package cn.byteforce.seedj.infra.local.im;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 内存 IM 适配器：store=memory（缺省）时自带全部存储。 */
class InMemoryImStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(InMemoryImStoreAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class, ImEnabledMarker::new)
                    .withBean(Clock.class, Clock::systemUTC);

    @Test
    @DisplayName("默认装配内存序号、会话、消息、游标与卡片动作存储")
    void memoryIsTheDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ImSequenceAllocator.class))
                            .isInstanceOf(AtomicLongSequenceAllocator.class);
                    assertThat(context.getBean(ImConversationStore.class))
                            .isInstanceOf(InMemoryImConversationStore.class);
                    assertThat(context.getBean(ImMessageStore.class))
                            .isInstanceOf(InMemoryImMessageStore.class);
                    assertThat(context.getBean(ImCursorStore.class))
                            .isInstanceOf(InMemoryImCursorStore.class);
                    assertThat(context.getBean(ImCardActionStore.class))
                            .isInstanceOf(InMemoryImCardActionStore.class);
                });
    }

    @Test
    @DisplayName("store=jdbc 时整体让位给 PostgreSQL 适配器")
    void jdbcStoreBacksOff() {
        runner.withPropertyValues("seedj.im.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ImConversationStore.class);
                            assertThat(context).doesNotHaveBean(ImCardActionStore.class);
                        });
    }

    @Test
    @DisplayName("已有存储 bean 不覆盖，enabled=false 时整体退出")
    void existingBeansAndSwitchesAreHonoured() {
        ImMessageStore messages = new InMemoryImMessageStore(Clock.systemUTC());

        runner.withBean(ImMessageStore.class, () -> messages)
                .run(context -> assertThat(context.getBean(ImMessageStore.class)).isSameAs(messages));

        runner.withPropertyValues("seedj.im.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ImMessageStore.class);
                            assertThat(context).doesNotHaveBean(ImConversationStore.class);
                        });
    }
}
