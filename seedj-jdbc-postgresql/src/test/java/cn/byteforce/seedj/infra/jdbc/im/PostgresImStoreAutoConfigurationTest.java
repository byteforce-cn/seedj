package cn.byteforce.seedj.infra.jdbc.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.im.ImEnabledMarker;
import cn.byteforce.seedj.im.spi.ImCardActionStore;
import cn.byteforce.seedj.im.spi.ImConversationStore;
import cn.byteforce.seedj.im.spi.ImCursorStore;
import cn.byteforce.seedj.im.spi.ImMessageStore;
import cn.byteforce.seedj.im.spi.ImSequenceAllocator;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL IM 适配器：store=jdbc 时自带存储与 schema 迁移。 */
class PostgresImStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(PostgresImStoreAutoConfiguration.class))
                    .withBean(ImEnabledMarker.class, ImEnabledMarker::new)
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(Clock.class, Clock::systemUTC);

    @Test
    @DisplayName("store=jdbc 时装配全部 JDBC 存储与迁移")
    void jdbcStoreIsWired() {
        runner.withPropertyValues("seedj.im.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(ImSequenceAllocator.class))
                                    .isInstanceOf(JdbcImSequenceAllocator.class);
                            assertThat(context.getBean(ImConversationStore.class))
                                    .isInstanceOf(JdbcImConversationStore.class);
                            assertThat(context.getBean(ImMessageStore.class))
                                    .isInstanceOf(JdbcImMessageStore.class);
                            assertThat(context.getBean(ImCursorStore.class))
                                    .isInstanceOf(JdbcImCursorStore.class);
                            assertThat(context.getBean(ImCardActionStore.class))
                                    .isInstanceOf(JdbcImCardActionStore.class);
                            assertThat(context).hasBean("postgresImSchemaCapability");
                        });
    }

    @Test
    @DisplayName("store=memory 时整体让位给内存适配器")
    void memoryStoreBacksOff() {
        runner.withPropertyValues("seedj.im.store=memory")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ImConversationStore.class);
                        });
    }

    @Test
    @DisplayName("enabled=false 或缺 JdbcTemplate 时不装配")
    void switchesAndMissingJdbcBackOff() {
        runner.withPropertyValues("seedj.im.store=jdbc", "seedj.im.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ImConversationStore.class));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresImStoreAutoConfiguration.class))
                .withBean(ImEnabledMarker.class, ImEnabledMarker::new)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("seedj.im.store=jdbc")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(ImConversationStore.class);
                        });
    }

    @Test
    @DisplayName("已有存储 bean 时让位")
    void existingBeansWin() {
        ImMessageStore messages = mock(ImMessageStore.class);

        runner.withPropertyValues("seedj.im.store=jdbc")
                .withBean(ImMessageStore.class, () -> messages)
                .run(
                        context ->
                                assertThat(context.getBean(ImMessageStore.class))
                                        .isSameAs(messages));
    }
}
