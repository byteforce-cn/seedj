package cn.byteforce.seedj.infra.jdbc.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.InfrastructureCapability;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** 装配开关：有 DataSource 时默认装配 PostgreSQL 账本，切换 provider 后整体退出。 */
class PostgresAiAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PostgresAiAutoConfiguration.class))
                    .withBean(DataSource.class, () -> mock(DataSource.class))
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    @DisplayName("默认装配：提供 PostgreSQL 账本与账本表迁移能力")
    void providesLedgerAndCapabilityByDefault() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DecisionLedger.class))
                            .isInstanceOf(PostgresDecisionLedger.class);
                    assertThat(context.getBean(InfrastructureCapability.class).getName())
                            .isEqualTo(AiSchemaCapability.NAME);
                });
    }

    @Test
    @DisplayName("provider 不是 postgres：不装配数据库账本")
    void disabledWhenProviderIsNotPostgres() {
        runner.withPropertyValues("seedj.ai.provider=memory")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(DecisionLedger.class);
                            assertThat(context).doesNotHaveBean(InfrastructureCapability.class);
                        });
    }

    @Test
    @DisplayName("已有账本：不覆盖")
    void keepsExistingLedger() {
        DecisionLedger existing = mock(DecisionLedger.class);
        runner.withBean(DecisionLedger.class, () -> existing)
                .run(
                        context ->
                                assertThat(context.getBean(DecisionLedger.class))
                                        .isSameAs(existing));
    }
}
