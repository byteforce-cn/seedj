package cn.byteforce.seedj.infra.jdbc.workflow;

import cn.byteforce.seedj.core.clock.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 工作流定时器扫描器：抢占到期 timer，写历史并把任务标记为 TIMEOUT。
 *
 * <p>使用 {@code FOR UPDATE SKIP LOCKED} 支持多实例并行；单条事务保证 timer、
 * task 与 history 一起提交。Provider 重启后直接扫表，不依赖内存调度线程。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class WorkflowTimerPoller {

    /** 任务超时状态（Provider 自定义状态，不扩展冻结的 HumanTaskStatus）。 */
    public static final String TIMEOUT = "TIMEOUT";

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactions;

    private final Clock clock;

    private final int batchSize;

    /**
     * 构造扫描器。
     *
     * @param jdbc         JDBC 模板
     * @param transactions 事务模板
     * @param clock        取时入口
     * @param batchSize    单次最多处理条数
     */
    public WorkflowTimerPoller(JdbcTemplate jdbc, TransactionTemplate transactions,
                               Clock clock, int batchSize) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 处理一批到期 timer。
     *
     * @return 本次处理条数
     */
    public int poll() {
        Integer processed = transactions.execute(status -> {
            Instant now = clock.now();
            List<Map<String, Object>> due = jdbc.queryForList(
                    "SELECT timer_id, instance_id, task_id FROM workflow_timer"
                            + " WHERE status = 'PENDING' AND due_at <= ?"
                            + " ORDER BY due_at LIMIT ? FOR UPDATE SKIP LOCKED",
                    Timestamp.from(now), batchSize);
            for (Map<String, Object> timer : due) {
                String timerId = (String) timer.get("timer_id");
                String instanceId = (String) timer.get("instance_id");
                String taskId = (String) timer.get("task_id");
                jdbc.update("UPDATE workflow_timer SET status = 'FIRED', fired_at = ?"
                        + " WHERE timer_id = ?", Timestamp.from(now), timerId);
                if (taskId != null) {
                    jdbc.update("UPDATE workflow_task SET status = ?, updated_at = ?,"
                                    + " completed_at = ? WHERE task_id = ? AND status = 'PENDING'",
                            TIMEOUT, Timestamp.from(now), Timestamp.from(now), taskId);
                }
                Long sequence = jdbc.queryForObject(
                        "SELECT nextval('workflow_history_seq')", Long.class);
                jdbc.update("INSERT INTO workflow_history (event_id, instance_id, sequence,"
                                + " event_type, task_id, occurred_at, attributes)"
                                + " VALUES (?, ?, ?, 'TIMEOUT', ?, ?, '{}'::jsonb)",
                        UUID.randomUUID().toString(), instanceId, sequence, taskId,
                        Timestamp.from(now));
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }
}
