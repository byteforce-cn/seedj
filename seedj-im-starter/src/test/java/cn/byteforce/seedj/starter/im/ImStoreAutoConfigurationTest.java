package cn.byteforce.seedj.starter.im;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 启停与前置检查：存储实现归适配器，starter 只留时钟与 jdbc 缺数据源的显式失败。 */
class ImStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(ImStoreAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class);

    @Test
    @DisplayName("默认提供 IM 时钟，不装配任何存储实现")
    void providesClockOnly() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context).doesNotHaveBean(ImConversationStore.class);
                });
    }

    @Test
    @DisplayName("store=jdbc 但没有 DataSource 时启动失败，不静默退回内存")
    void jdbcWithoutDataSourceFailsFast() {
        runner.withPropertyValues("seedj.im.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasMessageContaining(
                                            "seedj.im.store=jdbc requires a DataSource");
                        });
    }

    @Test
    @DisplayName("seedj.im.enabled=false 时连时钟也不装配")
    void masterSwitchDisablesTheRuntime() {
        runner.withPropertyValues("seedj.im.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(Clock.class);
                        });
    }
}
