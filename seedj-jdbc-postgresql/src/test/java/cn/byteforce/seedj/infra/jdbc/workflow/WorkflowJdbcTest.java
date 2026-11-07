package cn.byteforce.seedj.infra.jdbc.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.numbering.PostgresNumbering;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 工作流 schema、定时器扫描与编号序列的真实 PostgreSQL 语义。 */
@Testcontainers
class WorkflowJdbcTest {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private JdbcTemplate jdbc;

    private WorkflowSchemaCapability capability;

    @BeforeEach
    void initialize() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        capability = new WorkflowSchemaCapability(new JdbcMigrationRunner(dataSource));
        capability.initialize();
        jdbc.update("DELETE FROM workflow_history");
        jdbc.update("DELETE FROM workflow_timer");
        jdbc.update("DELETE FROM workflow_task");
        jdbc.update("DELETE FROM workflow_instance");
        jdbc.update("DELETE FROM workflow_definition");
    }

    @Test
    @DisplayName("schema capability 幂等且校验通过")
    void schemaCapabilityIsIdempotent() {
        capability.initialize();
        capability.validate();

        assertThat(capability.getName()).isEqualTo("workflow");
        assertThat(capability.getOrder()).isEqualTo(210);
        assertThat(capability.isReady()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL 编号：scope 隔离且严格递增")
    void numberingIsScopedAndIncreasing() {
        PostgresNumbering numbering = new PostgresNumbering(jdbc);

        assertThat(numbering.next("order_2026")).isEqualTo(1);
        assertThat(numbering.next("order_2026")).isEqualTo(2);
        assertThat(numbering.next("voucher_2026")).isEqualTo(1);
        assertThat(numbering.next("order_2027")).isEqualTo(1);
    }

    @Test
    @DisplayName("到期 timer 被抢占并标记任务 TIMEOUT")
    void overdueTimerMarksTaskTimeout() {
        seedInstance("inst-1", "task-1", NOW.minusSeconds(1));
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

        int processed = new WorkflowTimerPoller(jdbc, transactions, () -> NOW, 10).poll();

        assertThat(processed).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM workflow_task" + " WHERE task_id = 'task-1'",
                                String.class))
                .isEqualTo("TIMEOUT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM workflow_timer"
                                        + " WHERE timer_id = 'timer-task-1'",
                                String.class))
                .isEqualTo("FIRED");
        Map<String, Object> history =
                jdbc.queryForMap(
                        "SELECT event_type, task_id FROM workflow_history WHERE instance_id = 'inst-1'");
        assertThat(history)
                .containsEntry("event_type", "TIMEOUT")
                .containsEntry("task_id", "task-1");
    }

    @Test
    @DisplayName("未到期 timer 保持 PENDING")
    void futureTimerStaysPending() {
        seedInstance("inst-2", "task-2", NOW.plusSeconds(60));
        TransactionTemplate transactions =
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThat(new WorkflowTimerPoller(jdbc, transactions, () -> NOW, 10).poll()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM workflow_timer"
                                        + " WHERE timer_id = 'timer-task-2'",
                                String.class))
                .isEqualTo("PENDING");
    }

    private void seedInstance(String instanceId, String taskId, Instant dueAt) {
        jdbc.update(
                "INSERT INTO workflow_definition (namespace, name, major, minor,"
                        + " definition_json) VALUES ('default', 'approval', 1, 0,"
                        + " '{}'::jsonb) ON CONFLICT DO NOTHING");
        jdbc.update(
                "INSERT INTO workflow_instance (instance_id, definition_namespace,"
                        + " definition_name, definition_major, definition_minor, status,"
                        + " started_by, started_at, updated_at) VALUES"
                        + " (?, 'default', 'approval', 1, 0, 'RUNNING', 'alice', ?, ?)",
                instanceId,
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update(
                "INSERT INTO workflow_task (task_id, instance_id, step_name, kind,"
                        + " status, due_at, created_at, updated_at) VALUES"
                        + " (?, ?, 'approve', 'HUMAN_TASK', 'PENDING', ?, ?, ?)",
                taskId,
                instanceId,
                Timestamp.from(dueAt),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update(
                "INSERT INTO workflow_timer (timer_id, instance_id, task_id, due_at)"
                        + " VALUES (?, ?, ?, ?)",
                "timer-" + taskId,
                instanceId,
                taskId,
                Timestamp.from(dueAt));
    }
}
