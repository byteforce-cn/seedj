package cn.byteforce.seedj.starter.eis.domain.jdbc;

import cn.byteforce.seedj.starter.eis.domain.DomainEvent;
import cn.byteforce.seedj.starter.eis.domain.EventLogStore;
import cn.byteforce.seedj.starter.eis.domain.EventStream;
import cn.byteforce.seedj.starter.eis.domain.OptimisticLockingException;
import cn.byteforce.seedj.starter.eis.domain.internal.EventTypeRegistry;
import cn.byteforce.seedj.starter.eis.domain.spi.EventPayloadCodec;
import cn.byteforce.seedj.starter.eis.domain.spi.TenantResolver;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 事件日志的 PostgreSQL 实现（{@code evt_stream} / {@code evt_log}，部署侧辅助表）。
 *
 * <p><b>事务边界由调用方负责</b>：本类不开启事务。命令处理器必须在同一个事务里
 * 「加载聚合 → 追加事件」，否则乐观锁之后仍有窗口。</p>
 *
 * <p><b>乐观锁</b>：{@code UPDATE evt_stream SET current_version = current_version + n
 * WHERE stream_id = ? AND current_version = ?}；影响行数为 0 即冲突，异常里带 expected/actual。</p>
 *
 * <p><b>租户</b>：写入以 {@link TenantResolver} 的当前租户落列；租户必填时读写都带租户条件，
 * 解析不到租户直接失败（fail-closed）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresEventLogStore implements EventLogStore {

    private final JdbcTemplate jdbc;

    private final EventPayloadCodec codec;

    private final EventTypeRegistry eventTypes;

    private final TenantResolver tenantResolver;

    private final boolean tenantRequired;

    /**
     * 构造存储。
     *
     * @param jdbc           JDBC 模板（参与调用方事务）
     * @param codec          payload 编解码器
     * @param eventTypes     事件类型注册表（写入时自注册，读取时解析）
     * @param tenantResolver 当前租户解析
     * @param tenantRequired 租户是否必填
     */
    public PostgresEventLogStore(JdbcTemplate jdbc, EventPayloadCodec codec,
                                 EventTypeRegistry eventTypes, TenantResolver tenantResolver,
                                 boolean tenantRequired) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.eventTypes = eventTypes;
        this.tenantResolver = tenantResolver;
        this.tenantRequired = tenantRequired;
    }

    @Override
    public EventStream loadStream(String streamId) {
        return loadStream(streamId, 0L);
    }

    @Override
    public EventStream loadStream(String streamId, long fromVersion) {
        Long tenant = currentTenant();
        String tenantClause = tenant == null ? "" : " AND tenant_id = ?";

        List<Long> versions = jdbc.query(
                "SELECT current_version FROM evt_stream WHERE stream_id = ?" + tenantClause,
                (rs, rowNum) -> rs.getLong(1),
                tenant == null ? new Object[] {streamId} : new Object[] {streamId, tenant});
        if (versions.isEmpty()) {
            return EventStream.empty(streamId);
        }
        long currentVersion = versions.get(0);

        List<DomainEvent> events = jdbc.query(
                "SELECT event_type, schema_version, payload::text FROM evt_log"
                        + " WHERE stream_id = ? AND stream_version > ?" + tenantClause
                        + " ORDER BY stream_version",
                (rs, rowNum) -> codec.decode(rs.getString(1), rs.getInt(2), rs.getString(3)),
                tenant == null
                        ? new Object[] {streamId, fromVersion}
                        : new Object[] {streamId, fromVersion, tenant});
        return new EventStream(streamId, currentVersion, events);
    }

    @Override
    public void append(String streamId, String aggregateType, long expectedVersion,
                       List<DomainEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        Long tenant = currentTenant();
        ensureStream(streamId, aggregateType, tenant);

        String tenantClause = tenant == null ? "" : " AND tenant_id = ?";
        Object[] casArgs = tenant == null
                ? new Object[] {events.size(), streamId, expectedVersion}
                : new Object[] {events.size(), streamId, expectedVersion, tenant};
        int updated = jdbc.update(
                "UPDATE evt_stream SET current_version = current_version + ?, updated_at = clock_timestamp()"
                        + " WHERE stream_id = ? AND current_version = ?" + tenantClause, casArgs);
        if (updated == 0) {
            throw new OptimisticLockingException(streamId, expectedVersion, actualVersion(streamId));
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (int index = 0; index < events.size(); index++) {
            DomainEvent event = events.get(index);
            eventTypes.register(event.getClass());
            jdbc.update("""
                            INSERT INTO evt_log (event_id, stream_id, aggregate_type, event_type,
                                                 schema_version, stream_version, tenant_id, payload,
                                                 occurred_at, recorded_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                            """,
                    UUID.randomUUID(), streamId, aggregateType, eventTypes.nameOf(event.getClass()),
                    eventTypes.versionOf(event.getClass()), expectedVersion + index + 1, tenant,
                    codec.encode(event), toOffset(now), toOffset(now));
        }
    }

    private void ensureStream(String streamId, String aggregateType, Long tenant) {
        jdbc.update("""
                        INSERT INTO evt_stream (stream_id, aggregate_type, tenant_id, current_version)
                        VALUES (?, ?, ?, 0)
                        ON CONFLICT (stream_id) DO NOTHING
                        """,
                streamId, aggregateType, tenant);
    }

    private long actualVersion(String streamId) {
        List<Long> versions = jdbc.query("SELECT current_version FROM evt_stream WHERE stream_id = ?",
                (rs, rowNum) -> rs.getLong(1), streamId);
        return versions.isEmpty() ? 0L : versions.get(0);
    }

    private Long currentTenant() {
        Optional<Long> tenant = tenantResolver.currentTenantId();
        if (tenant.isEmpty() && tenantRequired) {
            throw new IllegalStateException("""
                    No tenant in context while accessing the event log.
                    Fix: set the tenant on RequestContext before the command, or set
                         byteforce.eis.domain.tenant-required=false only for single-tenant deployments.
                    """);
        }
        return tenant.orElse(null);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
