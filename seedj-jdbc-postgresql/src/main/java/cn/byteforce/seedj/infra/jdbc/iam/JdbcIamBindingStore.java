package cn.byteforce.seedj.infra.jdbc.iam;

import cn.byteforce.seedj.iam.authz.IamBinding;
import cn.byteforce.seedj.iam.authz.IamBindingStore;
import cn.byteforce.seedj.iam.authz.IamProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 授权绑定/代理存储：时点过滤在 SQL 完成。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class JdbcIamBindingStore implements IamBindingStore {

    private static final String INSERT_BINDING = """
            INSERT INTO iam_binding
                (id, principal, role, scope, valid_from, valid_to, recorded_at, superseded_at)
            VALUES (?, ?, ?, ?::jsonb, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                    ?::timestamptz)
            """;

    private static final String INSERT_PROXY = """
            INSERT INTO iam_proxy
                (id, principal, agent, scope, valid_from, valid_to, recorded_at, superseded_at)
            VALUES (?, ?, ?, ?::jsonb, ?::timestamptz, ?::timestamptz, ?::timestamptz,
                    ?::timestamptz)
            """;

    private static final RowMapper<IamBinding> BINDING_MAPPER = (rs, rowNum) -> new IamBinding(
            rs.getString("id"),
            rs.getString("principal"),
            rs.getString("role"),
            scope(rs.getString("scope")),
            instant(rs, "valid_from"),
            instant(rs, "valid_to"),
            instant(rs, "recorded_at"),
            instant(rs, "superseded_at"));

    private static final RowMapper<IamProxy> PROXY_MAPPER = (rs, rowNum) -> new IamProxy(
            rs.getString("id"),
            rs.getString("principal"),
            rs.getString("agent"),
            scope(rs.getString("scope")),
            instant(rs, "valid_from"),
            instant(rs, "valid_to"),
            instant(rs, "recorded_at"),
            instant(rs, "superseded_at"));

    private final JdbcTemplate jdbc;

    public JdbcIamBindingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(IamBinding binding) {
        jdbc.update(INSERT_BINDING, binding.id(), binding.principal(), binding.role(),
                IamJdbcJson.map(new java.util.LinkedHashMap<>(binding.scope())),
                timestamp(binding.validFrom()), timestamp(binding.validTo()),
                timestamp(binding.recordedAt()), timestamp(binding.supersededAt()));
    }

    @Override
    public void save(IamProxy proxy) {
        jdbc.update(INSERT_PROXY, proxy.id(), proxy.principal(), proxy.agent(),
                IamJdbcJson.map(new java.util.LinkedHashMap<>(proxy.scope())),
                timestamp(proxy.validFrom()), timestamp(proxy.validTo()),
                timestamp(proxy.recordedAt()), timestamp(proxy.supersededAt()));
    }

    @Override
    public List<IamBinding> effectiveBindings(String principal, Instant asOf,
                                              Instant recordedAsOf) {
        List<Object> args = new ArrayList<>();
        args.add(principal);
        args.add(timestamp(asOf));
        args.add(timestamp(asOf));
        String sql = "SELECT * FROM iam_binding WHERE principal = ?"
                + " AND valid_from <= ?::timestamptz"
                + " AND (valid_to IS NULL OR valid_to > ?::timestamptz)"
                + recordedClause(recordedAsOf, args)
                + " ORDER BY valid_from";
        return jdbc.query(sql, BINDING_MAPPER, args.toArray());
    }

    @Override
    public List<IamProxy> effectiveProxies(String agent, Instant asOf, Instant recordedAsOf) {
        List<Object> args = new ArrayList<>();
        args.add(agent);
        args.add(timestamp(asOf));
        args.add(timestamp(asOf));
        String sql = "SELECT * FROM iam_proxy WHERE agent = ?"
                + " AND valid_from <= ?::timestamptz"
                + " AND (valid_to IS NULL OR valid_to > ?::timestamptz)"
                + recordedClause(recordedAsOf, args)
                + " ORDER BY valid_from";
        return jdbc.query(sql, PROXY_MAPPER, args.toArray());
    }

    private static String recordedClause(Instant recordedAsOf, List<Object> args) {
        if (recordedAsOf == null) {
            return " AND superseded_at IS NULL";
        }
        args.add(timestamp(recordedAsOf));
        args.add(timestamp(recordedAsOf));
        return " AND recorded_at <= ?::timestamptz"
                + " AND (superseded_at IS NULL OR superseded_at > ?::timestamptz)";
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Map<String, String> scope(String json) {
        Map<String, String> scope = new java.util.LinkedHashMap<>();
        IamJdbcJson.map(json).forEach((key, value) -> {
            if (value != null) {
                scope.put(key, String.valueOf(value));
            }
        });
        return Map.copyOf(scope);
    }
}
