package cn.byteforce.seedj.conformance;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.infra.jdbc.JdbcMigrationRunner;
import cn.byteforce.seedj.infra.jdbc.workflow.WorkflowSchemaCapability;
import cn.byteforce.seedj.infra.jdbc.workflow.WorkflowTimerPoller;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流持久化基础：重启后实例/任务仍在，定时器到期后任务进入 TIMEOUT。
 *
 * <p>完整 JDBC 状态机（定义注册、动作推进）按 ADR-0002 作为 M5 后续工作；本测试固定
 * 存储 schema、跨连接可读与 timer 扫描三个 1.0 之后不能再退回的语义。</p>
 */
@Testcontainers
class WorkflowPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private JdbcTemplate jdbc;

    private Clock clock = () -> NOW;

    @BeforeEach
    void initialize() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new WorkflowSchemaCapability(new JdbcMigrationRunner(dataSource)).initialize();
        jdbc.update("DELETE FROM workflow_history");
        jdbc.update("DELETE FROM workflow_timer");
        jdbc.update("DELETE FROM workflow_task");
        jdbc.update("DELETE FROM workflow_instance");
        jdbc.update("DELETE FROM workflow_definition");
    }

    private void seedInstanceAndTask(String instanceId, String taskId, Instant dueAt) {
        jdbc.update("INSERT INTO workflow_definition (namespace, name, major, minor,"
                        + " definition_json) VALUES ('default', 'approval', 1, 0,"
                        + " '{}'::jsonb) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO workflow_instance (instance_id, definition_namespace,"
                        + " definition_name, definition_major, definition_minor, status,"
                        + " started_by, started_at, updated_at) VALUES"
                        + " (?, 'default', 'approval', 1, 0, 'RUNNING', 'alice', ?, ?)",
                instanceId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO workflow_task (task_id, instance_id, step_name, kind,"
                        + " status, due_at, created_at, updated_at) VALUES"
                        + " (?, ?, 'approve', 'HUMAN_TASK', 'PENDING', ?, ?, ?)",
                taskId, instanceId, Timestamp.from(dueAt), Timestamp.from(NOW),
                Timestamp.from(NOW));
        jdbc.update("INSERT INTO workflow_timer (timer_id, instance_id, task_id, due_at)"
                        + " VALUES (?, ?, ?, ?)", "timer-" + taskId, instanceId, taskId,
                Timestamp.from(dueAt));
    }

    @Test
    @DisplayName("新连接（模拟重启）仍能读到实例与任务")
    void instanceAndTaskSurviveNewConnection() {
        seedInstanceAndTask("inst-1", "task-1", NOW.plusSeconds(60));

        DriverManagerDataSource restarted = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate after = new JdbcTemplate(restarted);

        assertThat(after.queryForObject("SELECT status FROM workflow_instance"
                + " WHERE instance_id = 'inst-1'", String.class)).isEqualTo("RUNNING");
        assertThat(after.queryForObject("SELECT status FROM workflow_task"
                + " WHERE task_id = 'task-1'", String.class)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("到期 timer：任务 TIMEOUT、timer FIRED、写 TIMEOUT 历史")
    void overdueTimerMarksTaskTimeout() {
        seedInstanceAndTask("inst-2", "task-2", NOW.minusSeconds(1));
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        int processed = new WorkflowTimerPoller(jdbc, transactions, clock, 10).poll();

        assertThat(processed).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_task"
                + " WHERE task_id = 'task-2'", String.class)).isEqualTo("TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_timer"
                + " WHERE timer_id = 'timer-task-2'", String.class)).isEqualTo("FIRED");
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT event_type, task_id FROM workflow_history"
                        + " WHERE instance_id = 'inst-2'");
        assertThat(history).containsEntry("event_type", "TIMEOUT")
                .containsEntry("task_id", "task-2");
    }

    @Test
    @DisplayName("未到期 timer 不处理")
    void futureTimerIsLeftPending() {
        seedInstanceAndTask("inst-3", "task-3", NOW.plusSeconds(60));
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThat(new WorkflowTimerPoller(jdbc, transactions, clock, 10).poll()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM workflow_task"
                + " WHERE task_id = 'task-3'", String.class)).isEqualTo("PENDING");
    }
}
