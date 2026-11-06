package cn.byteforce.seedj.domain.jdbc;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Sealing;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL 双时间轴对象存储。
 *
 * <p>封口/追加语义委托 {@link Sealing}；本类只负责 SQL 与行映射。调用方应在
 * 事务内调用 {@link #append(AppendRequest)}（命令运行时保证），以便对象行、命令记录、
 * 审计与事件一起提交或回滚。</p>
 *
 * <p>对象类型必须先在 {@link TemporalTables} 注册；未注册类型抛
 * {@link IllegalArgumentException}（消息含类型名），不让底层 SQL 异常穿透。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class PostgresTemporalStore implements TemporalStore {

    private static final String SELECT_ALL = "SELECT * FROM ";

    private final JdbcTemplate jdbc;

    private final Clock clock;

    private final TemporalTables tables;

    /**
     * 构造存储。
     *
     * @param jdbc   JDBC 模板
     * @param clock  取时入口
     * @param tables 对象类型注册表
     */
    public PostgresTemporalStore(JdbcTemplate jdbc, Clock clock, TemporalTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    @Override
    public Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query) {
        Objects.requireNonNull(query, "query");
        TemporalTable table = tables.require(objectType);
        StringBuilder sql = new StringBuilder(SELECT_ALL).append(table.tableName())
                .append(" WHERE id = ?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (query.asOf() == null && query.recordedAsOf() == null) {
            sql.append(" AND superseded_at IS NULL AND valid_to IS NULL");
        } else {
            if (query.asOf() != null) {
                sql.append(" AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
                args.add(timestamp(query.asOf()));
                args.add(timestamp(query.asOf()));
            }
            if (query.recordedAsOf() != null) {
                sql.append(" AND recorded_at <= ?")
                        .append(" AND (superseded_at IS NULL OR superseded_at > ?)");
                args.add(timestamp(query.recordedAsOf()));
                args.add(timestamp(query.recordedAsOf()));
            }
        }
        sql.append(" ORDER BY version DESC LIMIT 1");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TemporalRow row = toRow(table, rows.get(0));
        if (query.minVersion() != null && row.version().version() < query.minVersion()) {
            long readable = row.version().version();
            throw new ContractException(ContractErrorCode.READ_MODEL_NOT_READY,
                    "readable version " + readable + " is below required " + query.minVersion(),
                    Map.of("minVersion", query.minVersion(), "readableVersion", readable), null);
        }
        return Optional.of(row);
    }

    @Override
    public AppendResult append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        TemporalTable table = tables.require(request.objectType());
        List<Map<String, Object>> rows = jdbc.queryForList(SELECT_ALL + table.tableName()
                + " WHERE id = ? AND superseded_at IS NULL FOR UPDATE", request.id());
        TemporalRow current = rows.isEmpty() ? null : toRow(table, rows.get(0));

        AppendResult result = Sealing.next(request, current, clock.now());
        result.superseded().ifPresent(sealed -> seal(table, sealed.version()));
        insert(table, result.appended());
        return result;
    }

    @Override
    public Page<TemporalRow> search(TemporalSearch search) {
        Objects.requireNonNull(search, "search");
        TemporalTable table = tables.require(search.objectType());
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        search.equals().forEach((field, value) -> {
            if (!table.filterableColumns().contains(field)) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "unknown or non-filterable field: " + field);
            }
            Object coerced = table.fieldTypes().containsKey(field)
                    ? table.fieldTypes().get(field).coerce(value) : value;
            where.append(" AND ").append(field).append(" = ?");
            args.add(TemporalValues.toJdbc(table.fieldTypes().get(field), coerced));
        });
        if (search.sortField() != null
                && !table.filterableColumns().contains(search.sortField())) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "unknown or non-sortable field: " + search.sortField());
        }
        if (search.asOf() != null) {
            where.append(" AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
            args.add(timestamp(search.asOf()));
            args.add(timestamp(search.asOf()));
        }
        if (search.recordedAsOf() != null) {
            where.append(" AND recorded_at <= ?")
                    .append(" AND (superseded_at IS NULL OR superseded_at > ?)");
            args.add(timestamp(search.recordedAsOf()));
            args.add(timestamp(search.recordedAsOf()));
        }

        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM "
                + table.tableName() + where, Long.class, args.toArray());
        String sort = search.sortField() == null ? "t.id" : "t." + search.sortField();
        String sql = "SELECT * FROM (SELECT DISTINCT ON (id) * FROM " + table.tableName()
                + where + " ORDER BY id, version DESC) t ORDER BY " + sort + " "
                + search.direction() + ", t.id LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(search.page().size());
        pageArgs.add(search.page().offset());

        List<TemporalRow> items = jdbc.queryForList(sql, pageArgs.toArray()).stream()
                .map(row -> toRow(table, row))
                .toList();
        return new Page<>(items, total == null ? 0 : total, search.page().page(),
                search.page().size());
    }

    @Override
    public List<TemporalRow> history(String objectType, UUID id) {
        TemporalTable table = tables.require(objectType);
        return jdbc.queryForList(SELECT_ALL + table.tableName()
                        + " WHERE id = ? ORDER BY recorded_at ASC", id)
                .stream()
                .map(row -> toRow(table, row))
                .toList();
    }

    private void seal(TemporalTable table, ObjectVersion sealed) {
        int updated = jdbc.update("UPDATE " + table.tableName()
                        + " SET superseded_at = ? WHERE id = ? AND recorded_at = ?"
                        + " AND superseded_at IS NULL",
                timestamp(sealed.supersededAt()), sealed.id(), timestamp(sealed.recordedAt()));
        if (updated != 1) {
            throw new IllegalStateException("failed to seal object version: " + sealed.id()
                    + "@" + sealed.recordedAt());
        }
    }

    private void insert(TemporalTable table, TemporalRow row) {
        ObjectVersion version = row.version();
        List<String> columns = new ArrayList<>(List.of(
                "id", "version", "valid_from", "valid_to", "recorded_at", "superseded_at",
                "created_by", "created_at", "updated_by", "updated_at"));
        List<Object> args = new ArrayList<>();
        args.add(version.id());
        args.add(version.version());
        args.add(timestamp(version.validFrom()));
        args.add(timestamp(version.validTo()));
        args.add(timestamp(version.recordedAt()));
        args.add(timestamp(version.supersededAt()));
        args.add(version.createdBy());
        args.add(timestamp(version.createdAt()));
        args.add(version.updatedBy());
        args.add(timestamp(version.updatedAt()));
        for (String column : table.columns().keySet()) {
            columns.add(column);
            args.add(TemporalValues.toJdbc(table.fieldTypes().get(column),
                    row.fields().get(column)));
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        jdbc.update("INSERT INTO " + table.tableName() + " (" + String.join(", ", columns)
                + ") VALUES (" + placeholders + ")", args.toArray());
    }

    private static TemporalRow toRow(TemporalTable table, Map<String, Object> row) {
        ObjectVersion version = new ObjectVersion(
                (UUID) row.get("id"),
                ((Number) row.get("version")).longValue(),
                instant(row.get("valid_from")),
                instant(row.get("valid_to")),
                instant(row.get("recorded_at")),
                instant(row.get("superseded_at")),
                (UUID) row.get("created_by"),
                instant(row.get("created_at")),
                (UUID) row.get("updated_by"),
                instant(row.get("updated_at")));
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String column : table.columns().keySet()) {
            Object value = TemporalValues.fromJdbc(table.fieldTypes().get(column),
                    row.get(column));
            if (value != null) {
                fields.put(column, value);
            }
        }
        return new TemporalRow(table.objectType(), version, fields);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalArgumentException("unsupported temporal value: " + value.getClass());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
