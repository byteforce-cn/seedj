package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.AppendRequest;
import cn.byteforce.seedj.domain.AppendResult;
import cn.byteforce.seedj.domain.ObjectVersion;
import cn.byteforce.seedj.domain.Page;
import cn.byteforce.seedj.domain.PageCursor;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.Sealing;
import cn.byteforce.seedj.domain.TemporalFilter;
import cn.byteforce.seedj.domain.TemporalQuery;
import cn.byteforce.seedj.domain.TemporalRow;
import cn.byteforce.seedj.domain.TemporalSearch;
import cn.byteforce.seedj.domain.TemporalStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 双时间轴对象存储。
 *
 * <p>封口/追加语义委托 {@link Sealing}；本类只负责 SQL 与行映射。调用方应在 事务内调用 {@link
 * #append(AppendRequest)}（命令运行时保证），以便对象行、命令记录、 审计与事件一起提交或回滚。
 *
 * <p>对象类型必须先在 {@link TemporalTables} 注册；未注册类型抛 {@link IllegalArgumentException}（消息含类型名），不让底层 SQL
 * 异常穿透。
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
     * @param jdbc JDBC 模板
     * @param clock 取时入口
     * @param tables 对象类型注册表
     */
    public PostgresTemporalStore(JdbcTemplate jdbc, Clock clock, TemporalTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tables = Objects.requireNonNull(tables, "tables");
    }

    @Override
    public Optional<TemporalRow> find(
            String objectType, UUID id, TemporalQuery query, ScopePredicate scope) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(scope, "scope");
        TemporalTable table = tables.require(objectType);
        StringBuilder selected = new StringBuilder(" WHERE id = ?");
        List<Object> selectedArgs = new ArrayList<>();
        selectedArgs.add(id);
        if (query.asOf() == null && query.recordedAsOf() == null) {
            selected.append(" AND superseded_at IS NULL AND valid_to IS NULL");
        } else {
            if (query.asOf() != null) {
                selected.append(" AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
                selectedArgs.add(timestamp(query.asOf()));
                selectedArgs.add(timestamp(query.asOf()));
            }
            if (query.recordedAsOf() != null) {
                selected.append(" AND recorded_at <= ?")
                        .append(" AND (superseded_at IS NULL OR superseded_at > ?)");
                selectedArgs.add(timestamp(query.recordedAsOf()));
                selectedArgs.add(timestamp(query.recordedAsOf()));
            }
        }
        ScopeSql.Clause scoped = ScopeSql.compile(scope, table);
        String sql =
                "SELECT * FROM (SELECT * FROM "
                        + table.tableName()
                        + selected
                        + " ORDER BY version DESC LIMIT 1) t WHERE ("
                        + scoped.sql()
                        + ")";
        List<Object> args = new ArrayList<>(selectedArgs);
        args.addAll(scoped.args());

        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TemporalRow row = toRow(table, rows.get(0));
        if (query.minVersion() != null && row.version().version() < query.minVersion()) {
            long readable = row.version().version();
            throw new ContractException(
                    ContractErrorCode.READ_MODEL_NOT_READY,
                    "readable version " + readable + " is below required " + query.minVersion(),
                    Map.of("minVersion", query.minVersion(), "readableVersion", readable),
                    null);
        }
        return Optional.of(row);
    }

    @Override
    public AppendResult append(AppendRequest request) {
        Objects.requireNonNull(request, "request");
        TemporalTable table = tables.require(request.objectType());
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        SELECT_ALL
                                + table.tableName()
                                + " WHERE id = ? AND superseded_at IS NULL FOR UPDATE",
                        request.id());
        TemporalRow current = rows.isEmpty() ? null : toRow(table, rows.get(0));
        requireExpectedVersion(request, current);

        AppendResult result = Sealing.next(request, current, clock.now());
        result.superseded().ifPresent(previous -> seal(table, previous.version()));
        insert(table, result.appended());
        return result;
    }

    private static void requireExpectedVersion(AppendRequest request, TemporalRow current) {
        Long expected = request.expectedVersion();
        if (expected == null) {
            return;
        }
        long actual = current == null ? 0 : current.version().version();
        if (expected.longValue() != actual) {
            throw new ContractException(
                    ContractErrorCode.VERSION_CONFLICT,
                    "expected version " + expected + " but current is " + actual,
                    Map.of("expected_version", expected, "current_version", actual),
                    null);
        }
    }

    @Override
    public Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope) {
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(scope, "scope");
        TemporalTable table = tables.require(search.objectType());
        if (search.sortField() != null && !table.sortableColumns().contains(search.sortField())) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD,
                    "unknown or non-sortable field: " + search.sortField());
        }

        List<Object> baseArgs = new ArrayList<>();
        String base;
        if (search.asOf() == null && search.recordedAsOf() == null) {
            // 当前视图快路径：superseded_at IS NULL 命中 uq_*_current，不再 DISTINCT ON 全表。
            base = "SELECT * FROM " + table.tableName() + " WHERE superseded_at IS NULL";
        } else {
            StringBuilder selected = new StringBuilder(" WHERE 1 = 1");
            if (search.asOf() != null) {
                selected.append(" AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
                baseArgs.add(timestamp(search.asOf()));
                baseArgs.add(timestamp(search.asOf()));
            }
            if (search.recordedAsOf() != null) {
                selected.append(" AND recorded_at <= ?")
                        .append(" AND (superseded_at IS NULL OR superseded_at > ?)");
                baseArgs.add(timestamp(search.recordedAsOf()));
                baseArgs.add(timestamp(search.recordedAsOf()));
            }
            base =
                    "SELECT DISTINCT ON (id) * FROM "
                            + table.tableName()
                            + selected
                            + " ORDER BY id, version DESC";
        }

        List<Object> outerArgs = new ArrayList<>();
        StringBuilder outer = new StringBuilder(" WHERE 1 = 1");
        ScopeSql.Clause scoped = ScopeSql.compile(scope, table);
        outer.append(" AND (").append(scoped.sql()).append(")");
        outerArgs.addAll(scoped.args());
        search.equals()
                .forEach(
                        (field, value) -> {
                            outer.append(" AND ").append(field).append(" = ?");
                            outerArgs.add(filterValue(table, field, value));
                        });
        for (TemporalFilter filter : search.filters()) {
            outer.append(" AND (").append(filterSql(filter, table, outerArgs)).append(')');
        }
        String sort = search.sortField() == null ? "t.id" : "t." + search.sortField();
        if (search.cursor() != null) {
            appendCursor(outer, outerArgs, search, table);
        }

        List<Object> args = new ArrayList<>(baseArgs);
        args.addAll(outerArgs);
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (" + base + ") t" + outer,
                        Long.class,
                        args.toArray());
        String sql =
                "SELECT * FROM ("
                        + base
                        + ") t"
                        + outer
                        + " ORDER BY "
                        + sort
                        + " "
                        + search.direction()
                        + ", t.id LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(search.page().size() + 1);
        pageArgs.add(search.page().offset());

        List<TemporalRow> fetched =
                jdbc.queryForList(sql, pageArgs.toArray()).stream()
                        .map(row -> toRow(table, row))
                        .toList();
        boolean hasMore = fetched.size() > search.page().size();
        List<TemporalRow> items =
                hasMore ? List.copyOf(fetched.subList(0, search.page().size())) : fetched;
        PageCursor next =
                hasMore && !items.isEmpty()
                        ? cursorOf(items.get(items.size() - 1), search.sortField())
                        : null;
        return new Page<>(
                items, total == null ? 0 : total, search.page().page(), search.page().size(), next);
    }

    private static void appendCursor(
            StringBuilder outer, List<Object> args, TemporalSearch search, TemporalTable table) {
        PageCursor cursor = search.cursor();
        String operator =
                search.direction() == cn.byteforce.seedj.domain.SortDirection.DESC ? "<" : ">";
        if (search.sortField() == null) {
            outer.append(" AND t.id ").append(operator).append(" ?");
            args.add(cursor.id());
            return;
        }
        Object value = sortValue(table, search.sortField(), cursor.sortValue());
        outer.append(" AND (t.")
                .append(search.sortField())
                .append(' ')
                .append(operator)
                .append(" ? OR (t.")
                .append(search.sortField())
                .append(" = ? AND t.id ")
                .append(operator)
                .append(" ?))");
        args.add(value);
        args.add(value);
        args.add(cursor.id());
    }

    private static PageCursor cursorOf(TemporalRow row, String sortField) {
        if (sortField == null) {
            return new PageCursor(null, null, row.version().id());
        }
        Object value = row.fields().get(sortField);
        return value == null ? null : new PageCursor(sortField, value, row.version().id());
    }

    private static String filterSql(TemporalFilter filter, TemporalTable table, List<Object> args) {
        return switch (filter) {
            case TemporalFilter.Eq eq -> {
                args.add(filterValue(table, eq.field(), eq.value()));
                yield eq.field() + " = ?";
            }
            case TemporalFilter.Ne ne -> {
                args.add(filterValue(table, ne.field(), ne.value()));
                yield ne.field() + " <> ?";
            }
            case TemporalFilter.In in -> {
                if (in.values().isEmpty()) {
                    yield "1 = 0";
                }
                StringBuilder sql = new StringBuilder(in.field()).append(" IN (");
                for (int i = 0; i < in.values().size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append('?');
                    args.add(filterValue(table, in.field(), in.values().get(i)));
                }
                yield sql.append(')').toString();
            }
            case TemporalFilter.Gt gt -> {
                args.add(filterValue(table, gt.field(), gt.value()));
                yield gt.field() + " > ?";
            }
            case TemporalFilter.Gte gte -> {
                args.add(filterValue(table, gte.field(), gte.value()));
                yield gte.field() + " >= ?";
            }
            case TemporalFilter.Lt lt -> {
                args.add(filterValue(table, lt.field(), lt.value()));
                yield lt.field() + " < ?";
            }
            case TemporalFilter.Lte lte -> {
                args.add(filterValue(table, lte.field(), lte.value()));
                yield lte.field() + " <= ?";
            }
            case TemporalFilter.Between between -> {
                args.add(filterValue(table, between.field(), between.lower()));
                args.add(filterValue(table, between.field(), between.upper()));
                yield between.field() + " BETWEEN ? AND ?";
            }
            case TemporalFilter.Like like -> {
                args.add(like.pattern());
                yield like.field() + " LIKE ?";
            }
            case TemporalFilter.And and -> {
                if (and.parts().isEmpty()) {
                    yield "1 = 1";
                }
                StringBuilder sql = new StringBuilder("(");
                for (int i = 0; i < and.parts().size(); i++) {
                    if (i > 0) {
                        sql.append(" AND ");
                    }
                    sql.append('(').append(filterSql(and.parts().get(i), table, args)).append(')');
                }
                yield sql.append(')').toString();
            }
            case TemporalFilter.Or or -> {
                if (or.parts().isEmpty()) {
                    yield "1 = 0";
                }
                StringBuilder sql = new StringBuilder("(");
                for (int i = 0; i < or.parts().size(); i++) {
                    if (i > 0) {
                        sql.append(" OR ");
                    }
                    sql.append('(').append(filterSql(or.parts().get(i), table, args)).append(')');
                }
                yield sql.append(')').toString();
            }
            case TemporalFilter.Not not -> "NOT (" + filterSql(not.part(), table, args) + ")";
        };
    }

    private static Object sortValue(TemporalTable table, String field, Object value) {
        if (!table.columns().containsKey(field)) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "unknown sort field: " + field);
        }
        if (!table.fieldTypes().containsKey(field)) {
            return value;
        }
        Object coerced = table.fieldTypes().get(field).coerce(value);
        return TemporalValues.toJdbc(table.fieldTypes().get(field), coerced);
    }

    private static Object filterValue(TemporalTable table, String field, Object value) {
        if (!table.filterableColumns().contains(field)) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "unknown or non-filterable field: " + field);
        }
        if (!table.fieldTypes().containsKey(field)) {
            return value;
        }
        Object coerced = table.fieldTypes().get(field).coerce(value);
        return TemporalValues.toJdbc(table.fieldTypes().get(field), coerced);
    }

    @Override
    public List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope) {
        Objects.requireNonNull(scope, "scope");
        TemporalTable table = tables.require(objectType);
        ScopeSql.Clause scoped = ScopeSql.compile(scope, table);
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(scoped.args());
        return jdbc
                .queryForList(
                        SELECT_ALL
                                + table.tableName()
                                + " WHERE id = ? AND ("
                                + scoped.sql()
                                + ") ORDER BY recorded_at ASC",
                        args.toArray())
                .stream()
                .map(row -> toRow(table, row))
                .toList();
    }

    private void seal(TemporalTable table, ObjectVersion sealed) {
        int updated =
                jdbc.update(
                        "UPDATE "
                                + table.tableName()
                                + " SET superseded_at = ? WHERE id = ? AND recorded_at = ?"
                                + " AND superseded_at IS NULL",
                        timestamp(sealed.supersededAt()),
                        sealed.id(),
                        timestamp(sealed.recordedAt()));
        if (updated != 1) {
            throw new IllegalStateException(
                    "failed to seal object version: " + sealed.id() + "@" + sealed.recordedAt());
        }
    }

    private void insert(TemporalTable table, TemporalRow row) {
        ObjectVersion version = row.version();
        List<String> columns =
                new ArrayList<>(
                        List.of(
                                "id",
                                "version",
                                "valid_from",
                                "valid_to",
                                "recorded_at",
                                "superseded_at",
                                "created_by",
                                "created_at",
                                "updated_by",
                                "updated_at"));
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
            args.add(
                    TemporalValues.toJdbc(
                            table.fieldTypes().get(column), row.fields().get(column)));
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        jdbc.update(
                "INSERT INTO "
                        + table.tableName()
                        + " ("
                        + String.join(", ", columns)
                        + ") VALUES ("
                        + placeholders
                        + ")",
                args.toArray());
    }

    private static TemporalRow toRow(TemporalTable table, Map<String, Object> row) {
        ObjectVersion version =
                new ObjectVersion(
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
            Object value = TemporalValues.fromJdbc(table.fieldTypes().get(column), row.get(column));
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
