package cn.byteforce.seedj.lock.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.lock.LockProvider;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** 装配开关：显式开启才提供数据库锁。 */
class PostgresLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PostgresLockAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    @DisplayName("未开启：不提供 LockProvider")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockProvider.class);
        });
    }

    @Test
    @DisplayName("开启：提供 provider 与锁表迁移能力")
    void enabledProvidesProviderAndCapability() {
        runner.withPropertyValues("seedj.lock.postgres.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LockProvider.class);
            assertThat(context.getBean(LockProvider.class))
                    .isInstanceOf(PostgresLockProvider.class);
            assertThat(context).hasSingleBean(InfrastructureCapability.class);
        });
    }

    @Test
    @DisplayName("已有 LockProvider：不覆盖")
    void keepsExistingProvider() {
        LockProvider existing = LockProvider.disabled();
        runner.withPropertyValues("seedj.lock.postgres.enabled=true")
                .withBean(LockProvider.class, () -> existing)
                .run(context -> assertThat(context.getBean(LockProvider.class))
                        .isSameAs(existing));
    }
}
