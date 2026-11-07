package cn.byteforce.seedj.infra.jdbc.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.InfrastructureCapability;
import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.outbox.OutboxErrorCode;
import cn.byteforce.seedj.outbox.OutboxException;
import cn.byteforce.seedj.outbox.OutboxRecord;
import cn.byteforce.seedj.outbox.spi.OutboxStore;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresOutboxAutoConfigurationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj_autoconfig")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(dataSource)
                .execute(
                        """
                DROP TABLE IF EXISTS outbox_audit, outbox, seedj_schema_history
                """);
    }

    private ApplicationContextRunner runner() {
        java.util.concurrent.atomic.AtomicLong sequence =
                new java.util.concurrent.atomic.AtomicLong();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresOutboxAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(
                        IdGenerator.class,
                        () -> (IdGenerator<String>) () -> "id-" + sequence.incrementAndGet());
    }

    @Test
    @DisplayName("有 DataSource 时提供 OutboxStore，迁移由 InfrastructureCapability 执行")
    void providesStoreAndSchema() {
        runner().run(
                        context -> {
                            assertThat(context).hasSingleBean(OutboxStore.class);
                            assertThat(context).hasSingleBean(InfrastructureCapability.class);

                            context.getBean(InfrastructureCapability.class).initialize();

                            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                            Integer tables =
                                    jdbc.queryForObject(
                                            """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_name IN ('outbox', 'outbox_audit', 'seedj_schema_history')
                    """,
                                            Integer.class);
                            assertThat(tables).isEqualTo(3);
                        });
    }

    @Test
    @DisplayName("V2 迁移把 eventbus 列名改为通用 outbox 列名")
    void migrationsRenameColumns() {
        runner().run(
                        context -> {
                            context.getBean(InfrastructureCapability.class).initialize();

                            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                            Integer renamed =
                                    jdbc.queryForObject(
                                            """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'outbox'
                      AND column_name IN ('message_id', 'destination', 'content_type', 'content_version')
                    """,
                                            Integer.class);
                            assertThat(renamed).isEqualTo(4);

                            Integer oldColumns =
                                    jdbc.queryForObject(
                                            """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'outbox'
                      AND column_name IN ('event_id', 'topic', 'event_type', 'event_version')
                    """,
                                            Integer.class);
                            assertThat(oldColumns).isZero();
                        });
    }

    @Test
    @DisplayName("迁移重复执行是幂等的（多实例启动）")
    void migrationsAreIdempotent() {
        runner().run(
                        context -> {
                            InfrastructureCapability capability =
                                    context.getBean(InfrastructureCapability.class);
                            capability.initialize();
                            capability.initialize();
                            capability.validate();

                            Integer history =
                                    new JdbcTemplate(dataSource)
                                            .queryForObject(
                                                    "SELECT count(*) FROM seedj_schema_history",
                                                    Integer.class);
                            assertThat(history).isEqualTo(3);
                        });
    }

    @Test
    @DisplayName("seedj.outbox.enabled=false 时不提供 bean")
    void disabled() {
        runner().withPropertyValues("seedj.outbox.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxStore.class));
    }

    @Test
    @DisplayName("seedj.outbox.provider 不是 postgres 时不提供 bean")
    void otherProvider() {
        runner().withPropertyValues("seedj.outbox.provider=other")
                .run(context -> assertThat(context).doesNotHaveBean(OutboxStore.class));
    }

    @Test
    @DisplayName("没有 DataSource 时装配退让，不影响应用启动")
    void backsOffWithoutDataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PostgresOutboxAutoConfiguration.class))
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(OutboxStore.class));
    }

    @Test
    @DisplayName("事务外 append 抛 TX_REQUIRED（不是裸 IllegalStateException）")
    void appendRequiresTransaction() {
        new PostgresOutboxSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();
        OutboxStore store =
                new PostgresOutboxStore(
                        new JdbcTemplate(dataSource), () -> "outbox-" + seq.incrementAndGet());

        assertThatThrownBy(
                        () ->
                                store.append(
                                        OutboxRecord.of(
                                                "m-1",
                                                "destination",
                                                "k",
                                                "content",
                                                "p".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(OutboxException.class)
                .extracting(e -> ((OutboxException) e).getErrorCode())
                .isEqualTo(OutboxErrorCode.TX_REQUIRED);
    }
}
