package cn.byteforce.seedj.infra.jdbc.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.clock.Clocks;
import cn.byteforce.seedj.cqrs.spi.CommandStore;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ObjectSchemaRegistry;
import cn.byteforce.seedj.domain.TemporalStore;
import cn.byteforce.seedj.infra.jdbc.cqrs.PostgresCommandStore;
import cn.byteforce.seedj.infra.jdbc.domain.PostgresTemporalStore;
import cn.byteforce.seedj.infra.jdbc.domain.TemporalTables;
import cn.byteforce.seedj.infra.jdbc.read.PostgresQueryExecutor;
import cn.byteforce.seedj.read.QueryExecutor;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** 适配器自带的 provider 选择：显式 memory 时整体让位，默认有 JDBC 时 PostgreSQL。 */
class PostgresStoreAutoConfigurationTest {

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("employee", "hr_employee")
                    .field(FieldDefinition.of("name", FieldType.TEXT))
                    .build();

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(PostgresStoreAutoConfiguration.class))
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(Clock.class, Clocks::system)
                    .withBean(ObjectSchemaRegistry.class, () -> new ObjectSchemaRegistry(List.of(SCHEMA)));

    @Test
    @DisplayName("有 JDBC 且未显式指定 provider 时装配 PostgreSQL 存储与查询执行器")
    void postgresIsTheDefaultWhenJdbcIsPresent() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommandStore.class))
                            .isInstanceOf(PostgresCommandStore.class);
                    assertThat(context.getBean(TemporalStore.class))
                            .isInstanceOf(PostgresTemporalStore.class);
                    assertThat(context.getBean(QueryExecutor.class))
                            .isInstanceOf(PostgresQueryExecutor.class);
                    assertThat(context.getBean(TemporalTables.class).require("employee").columns())
                            .containsKey("name");
                });
    }

    @Test
    @DisplayName("显式 provider=memory 时适配器整体让位，由内存实现接管")
    void memoryProviderBacksOff() {
        runner.withPropertyValues(
                        "seedj.command.provider=memory", "seedj.object.provider=memory")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).doesNotHaveBean(CommandStore.class);
                            assertThat(context).doesNotHaveBean(TemporalStore.class);
                        });
    }

    @Test
    @DisplayName("有 DataSource 时注册三份 schema 迁移能力")
    void schemaCapabilitiesNeedDataSource() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasBean("postgresCommandSchemaCapability");
                            assertThat(context).hasBean("postgresTemporalSchemaCapability");
                            assertThat(context).hasBean("postgresEventSchemaCapability");
                        });
    }

    @Test
    @DisplayName("seedj.command.enabled=false 时不装配命令存储与迁移")
    void commandSwitchOff() {
        runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("seedj.command.enabled=false")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(CommandStore.class);
                            assertThat(context).doesNotHaveBean("postgresCommandSchemaCapability");
                        });
    }
}
