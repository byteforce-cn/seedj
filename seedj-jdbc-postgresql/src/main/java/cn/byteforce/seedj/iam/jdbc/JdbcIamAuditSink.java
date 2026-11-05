package cn.byteforce.seedj.iam.jdbc;

import cn.byteforce.seedj.core.security.SecurityAuditEvent;
import cn.byteforce.seedj.core.security.SecurityAuditSink;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL audit sink. Failures are swallowed by callers, not by this class. */
public class JdbcIamAuditSink implements SecurityAuditSink {

    private final JdbcTemplate jdbc;

    public JdbcIamAuditSink(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(SecurityAuditEvent event) {
        jdbc.update("""
                INSERT INTO iam_audit
                    (id, tenant_id, actor_id, action, resource_type, resource_id,
                     outcome, reason, occurred_at, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, event.id(), event.tenantId(), event.actorId(), event.action(),
                event.resourceType(), event.resourceId(), event.outcome(), event.reason(),
                java.sql.Timestamp.from(event.occurredAt()),
                IamJdbcJson.map(event.details()));
    }
}
