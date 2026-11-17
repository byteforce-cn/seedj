package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import cn.byteforce.seedj.infra.local.im.InMemoryImConversationStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 存储开关：默认内存、{@code jdbc} 缺数据源时启动失败、宿主处理器进入注册表。 */
class ImStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ImStoreAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class);

    @Test
    @DisplayName("默认走内存实现")
    void defaultsToMemoryStores() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(ImSequenceAllocator.class);
                    assertThat(context.getBean(ImConversationStore.class))
                            .isInstanceOf(InMemoryImConversationStore.class);
                    assertThat(context).hasSingleBean(ImMessageStore.class);
                    assertThat(context).hasSingleBean(ImCursorStore.class);
                });
    }

    @Test
    @DisplayName("store=jdbc 但没有 DataSource 时启动失败，不静默退回内存")
    void jdbcWithoutDataSourceFailsFast() {
        runner.withPropertyValues("seedj.im.store=jdbc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("seedj.im.enabled=false 时不装配任何存储")
    void masterSwitchDisablesStores() {
        runner.withPropertyValues("seedj.im.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ImConversationStore.class);
                            assertThat(context).doesNotHaveBean(ImMessageStore.class);
                            assertThat(context).doesNotHaveBean(ImCursorStore.class);
                        });
    }
}
