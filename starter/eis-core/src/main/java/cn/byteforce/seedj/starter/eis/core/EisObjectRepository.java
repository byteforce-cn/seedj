package cn.byteforce.seedj.starter.eis.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双时间轴对象存储（JDBC 实现）：创建 / 更新（封口）/ 时点查询 / 版本历史。
 *
 * <p><b>封口语义完全对齐参考内核</b>（{@code kernel._update_object}）：</p>
 * <ul>
 *   <li>更新 = 在单事务内"封旧行 + 追加新行"：旧行 {@code valid_to = superseded_at = now}，
 *       新行 {@code version + 1}、{@code valid_from = recorded_at = now}（半开区间相接）；
 *       系统时间轴 append-only（T-R006）；</li>
 *   <li>当前行经 {@code SELECT ... FOR UPDATE} 行锁串行化并发更新；</li>
 *   <li>{@code now} 不晚于当前行 {@code recorded_at} 时以 +1 微秒推进
 *       （T-R004 约束 {@code superseded_at > recorded_at} 恒成立）。</li>
 * </ul>
 *
 * <p><b>时点查询</b>（T-R002 半开区间）：{@code valid_from <= t AND (valid_to IS NULL OR valid_to > t)}
 * 与 {@code recorded_at <= t AND (superseded_at IS NULL OR superseded_at > t)}；
 * 两者均缺省 = 当前版本（{@code superseded_at IS NULL}）。语义与
 * {@code 03-time-contract/query-templates.sql} 一致。</p>
 *
 * <p><b>敏感列</b>：本存储按注册表读取全部列（凭证校验等内部流程需要原值）；
 * 出参投影（HTTP/事件/日志）由查询层按 {@link EisObjectType#getSensitiveFields()}
 * 剔除——脱敏是投影职责，不是存储职责。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class EisObjectRepository {

    private static final String[] META_COLUMNS = {
            "id", "version", "valid_from", "valid_to", "recorded_at", "superseded_at",
            "created_by", "created_at", "updated_by", "updated_at"
    };

    private final JdbcTemplate jdbc;

    private final EisObjectTypeRegistry registry;

    private final Map<String, TypeSql> sqlCache = new ConcurrentHashMap<>();

    /**
     * 构造对象存储。
     *
     * @param jdbc     JDBC 模板（参与外层事务）
     * @param registry 对象类型注册表
     */
    public EisObjectRepository(JdbcTemplate jdbc, EisObjectTypeRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        for (EisObjectType type : registry.all()) {
            sqlCache.put(type.getObjectType(), new TypeSql(type));
        }
    }

    /**
     * 创建对象（version = 1；valid_from = recorded_at = now）。
     *
     * @param objectType 对象类型名
     * @param id         对象 ID
     * @param data       领域数据（键须为注册字段）
     * @param actor      操作人
     * @param now        创建时刻
     * @return 新对象行
     */
    public EisObjectRow create(String objectType, UUID id, Map<String, Object> data, UUID actor, Instant now) {
        TypeSql sql = sql(objectType);
        Instant instant = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Map<String, Object> values = normalizeData(sql.type, data, false);
        jdbc.update(sql.insertSql, ps -> {
            int i = 1;
            ps.setObject(i++, id);
            ps.setLong(i++, 1L);
            setInstant(ps, i++, instant);
            setInstant(ps, i++, null);
            setInstant(ps, i++, instant);
            setInstant(ps, i++, null);
            ps.setObject(i++, actor);
            setInstant(ps, i++, instant);
            setUuid(ps, i++, null);
            setInstant(ps, i++, null);
            for (EisObjectField field : sql.type.getFields()) {
                setDomain(ps, i++, field.getKind(), values.get(field.getName()));
            }
        });
        return new EisObjectRow(objectType, id, 1L, instant, null, instant, null, actor, instant, null, null, values);
    }

    /**
     * 更新对象：封旧行 + 追加新行（单事务、行锁；语义见类文档）。
     *
     * @param objectType 对象类型名
     * @param id         对象 ID
     * @param updates    领域更新字段（合并到当前数据）
     * @param actor      操作人
     * @param now        更新时刻
     * @return 新对象行
     * @throws EisCommandRejectedException 当前记录不存在（EIS-404-001）
     */
    public EisObjectRow update(String objectType, UUID id, Map<String, Object> updates, UUID actor, Instant now) {
        TypeSql sql = sql(objectType);
        Instant instant = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        EisObjectRow current = lockCurrent(sql, id);
        if (current == null) {
            throw new EisCommandRejectedException("EIS-404-001", "对象不存在: " + objectType + "/" + id);
        }
        Map<String, Object> merged = new LinkedHashMap<>(current.getData());
        merged.putAll(normalizeData(sql.type, updates, true));

        Instant sealAt = instant.isAfter(current.getRecordedAt()) ? instant : current.getRecordedAt().plusNanos(1_000L);
        jdbc.update(sql.sealSql, ps -> {
            int i = 1;
            setInstant(ps, i++, sealAt);
            setInstant(ps, i++, sealAt);
            ps.setObject(i++, id);
            setInstant(ps, i, current.getRecordedAt());
        });
        jdbc.update(sql.insertSql, ps -> {
            int i = 1;
            ps.setObject(i++, id);
            ps.setLong(i++, current.getVersion() + 1);
            setInstant(ps, i++, sealAt);
            setInstant(ps, i++, null);
            setInstant(ps, i++, sealAt);
            setInstant(ps, i++, null);
            ps.setObject(i++, current.getCreatedBy());
            setInstant(ps, i++, current.getCreatedAt());
            ps.setObject(i++, actor);
            setInstant(ps, i++, sealAt);
            for (EisObjectField field : sql.type.getFields()) {
                setDomain(ps, i++, field.getKind(), merged.get(field.getName()));
            }
        });
        return new EisObjectRow(objectType, id, current.getVersion() + 1, sealAt, null, sealAt, null,
                current.getCreatedBy(), current.getCreatedAt(), actor, sealAt, merged);
    }

    /**
     * 当前版本查询（{@code superseded_at IS NULL}）。
     *
     * @param objectType 对象类型名
     * @param id         对象 ID
     * @return 当前行；不存在为空
     */
    public Optional<EisObjectRow> findCurrent(String objectType, UUID id) {
        TypeSql sql = sql(objectType);
        return jdbc.query(sql.selectCurrent, sql.rowMapper, id).stream().findFirst();
    }

    /**
     * 当前版本批量查询（附加等值条件）。
     *
     * @param objectType 对象类型名
     * @param criteria   等值条件（键须为注册字段）
     * @return 命中行列表（未声明顺序）
     */
    public List<EisObjectRow> findCurrentAllBy(String objectType, Map<String, Object> criteria) {
        TypeSql sql = sql(objectType);
        Condition condition = sql.condition(criteria);
        return jdbc.query(sql.selectCurrentBase + condition.sql, sql.rowMapper, condition.values);
    }

    /**
     * 当前版本查询（附加等值条件，取首行）。
     *
     * @param objectType 对象类型名
     * @param criteria   等值条件
     * @return 首行；无命中为空
     */
    public Optional<EisObjectRow> findCurrentBy(String objectType, Map<String, Object> criteria) {
        return findCurrentAllBy(objectType, criteria).stream().findFirst();
    }

    /**
     * 双时间轴时点查询（半开区间；缺省 = 当前）。
     *
     * @param objectType 对象类型名
     * @param id         对象 ID
     * @param validAt    业务时间切点（可空）
     * @param recordedAt 系统时间切点（可空）
     * @return 至多一个版本；无命中为空
     */
    public Optional<EisObjectRow> asOf(String objectType, UUID id, Instant validAt, Instant recordedAt) {
        TypeSql sql = sql(objectType);
        if (validAt == null && recordedAt == null) {
            return findCurrent(objectType, id);
        }
        StringBuilder statement = new StringBuilder(sql.selectAsOf);
        List<Object> args = new ArrayList<>();
        args.add(id);
        if (validAt != null) {
            statement.append(" AND \"valid_from\" <= ? AND (\"valid_to\" IS NULL OR \"valid_to\" > ?)");
            args.add(OffsetDateTime.ofInstant(validAt, ZoneOffset.UTC));
            args.add(OffsetDateTime.ofInstant(validAt, ZoneOffset.UTC));
        }
        if (recordedAt != null) {
            statement.append(" AND \"recorded_at\" <= ? AND (\"superseded_at\" IS NULL OR \"superseded_at\" > ?)");
            args.add(OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC));
            args.add(OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC));
        }
        statement.append(" ORDER BY \"version\" DESC LIMIT 1");
        return jdbc.query(statement.toString(), sql.rowMapper, args.toArray()).stream().findFirst();
    }

    /**
     * 版本历史（按 version 升序，含全部系统时间行）。
     *
     * @param objectType 对象类型名
     * @param id         对象 ID
     * @return 版本列表
     */
    public List<EisObjectRow> history(String objectType, UUID id) {
        TypeSql sql = sql(objectType);
        return jdbc.query(sql.selectHistory, sql.rowMapper, id);
    }

    private TypeSql sql(String objectType) {
        TypeSql sql = sqlCache.get(objectType);
        if (sql == null) {
            throw new IllegalArgumentException("未注册对象类型: " + objectType);
        }
        return sql;
    }

    /**
     * 行锁读取当前行（有界重试）。
     *
     * <p>并发更新者等待行锁提交后：READ COMMITTED 下新语句快照可见接力后的"新当前行"；
     * 若等待期间原行已被前者封口（谓词重检不过），重试可命中新当前行——这正是
     * "封口 + 追加"模型在行锁上的串行化路径。</p>
     */
    private EisObjectRow lockCurrent(TypeSql sql, UUID id) {
        for (int attempt = 0; attempt < 5; attempt++) {
            EisObjectRow row = jdbc.query(sql.selectCurrentForUpdate, sql.rowMapper, id).stream()
                    .findFirst().orElse(null);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    private Map<String, Object> normalizeData(EisObjectType type, Map<String, Object> data, boolean partial) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                EisObjectField field = type.getField(entry.getKey());
                if (field == null) {
                    throw new IllegalArgumentException("对象 " + type.getObjectType()
                            + " 未注册字段: " + entry.getKey());
                }
                values.put(entry.getKey(), entry.getValue());
            }
        }
        if (!partial) {
            for (EisObjectField field : type.getFields()) {
                if (!values.containsKey(field.getName())) {
                    values.put(field.getName(), null);
                }
            }
        }
        return values;
    }

    private static void setUuid(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.OTHER);
        } else {
            ps.setObject(index, value);
        }
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    private static void setDomain(PreparedStatement ps, int index, EisFieldKind kind, Object value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, nullType(kind));
            return;
        }
        switch (kind) {
            case TEXT -> ps.setString(index, (String) value);
            case BOOLEAN -> ps.setBoolean(index, (Boolean) value);
            case UUID -> ps.setObject(index, value);
            case TIMESTAMP -> ps.setObject(index, OffsetDateTime.ofInstant((Instant) value, ZoneOffset.UTC));
            case JSONB -> ps.setString(index, value instanceof JsonNode node ? node.toString() : String.valueOf(value));
            case TEXT_ARRAY -> ps.setArray(index, ps.getConnection().createArrayOf("text",
                    ((List<?>) value).toArray()));
        }
    }

    private static int nullType(EisFieldKind kind) {
        return switch (kind) {
            case TEXT -> Types.VARCHAR;
            case BOOLEAN -> Types.BOOLEAN;
            case UUID -> Types.OTHER;
            case TIMESTAMP -> Types.TIMESTAMP_WITH_TIMEZONE;
            case JSONB -> Types.OTHER;
            case TEXT_ARRAY -> Types.ARRAY;
        };
    }

    private static Object readDomain(ResultSet rs, int index, EisFieldKind kind) throws SQLException {
        return switch (kind) {
            case TEXT -> rs.getString(index);
            case BOOLEAN -> rs.getObject(index, Boolean.class);
            case UUID -> rs.getObject(index, UUID.class);
            case TIMESTAMP -> {
                OffsetDateTime value = rs.getObject(index, OffsetDateTime.class);
                yield value == null ? null : value.toInstant();
            }
            case JSONB -> CanonicalJson.parse(rs.getString(index));
            case TEXT_ARRAY -> {
                java.sql.Array array = rs.getArray(index);
                if (array == null) {
                    yield List.of();
                }
                String[] values = (String[]) array.getArray();
                yield List.of(values);
            }
        };
    }

    /**
     * 单类型 SQL 组装（表结构与列序在注册后固定，可缓存复用）。
     */
    private static final class TypeSql {

        private final EisObjectType type;

        private final String insertSql;

        private final String selectCurrentBase;

        private final String selectCurrent;

        private final String selectCurrentForUpdate;

        private final String selectAsOf;

        private final String selectHistory;

        private final String sealSql;

        private final RowMapper<EisObjectRow> rowMapper;

        private TypeSql(EisObjectType type) {
            this.type = type;
            String table = quote(type.getTableName());
            StringBuilder columns = new StringBuilder();
            for (String meta : META_COLUMNS) {
                if (columns.length() > 0) {
                    columns.append(", ");
                }
                columns.append(quote(meta));
            }
            for (EisObjectField field : type.getFields()) {
                columns.append(", ").append(quote(field.getName()));
            }
            String columnList = columns.toString();
            String placeholders = buildPlaceholders(type);
            this.insertSql = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";
            this.selectCurrentBase = "SELECT " + columnList + " FROM " + table + " WHERE \"superseded_at\" IS NULL";
            this.selectCurrent = selectCurrentBase + " AND \"id\" = ?";
            this.selectCurrentForUpdate = selectCurrent + " FOR UPDATE";
            this.selectAsOf = "SELECT " + columnList + " FROM " + table + " WHERE \"id\" = ?";
            this.selectHistory = "SELECT " + columnList + " FROM " + table
                    + " WHERE \"id\" = ? ORDER BY \"version\" ASC";
            this.sealSql = "UPDATE " + table + " SET \"valid_to\" = ?, \"superseded_at\" = ?"
                    + " WHERE \"id\" = ? AND \"recorded_at\" = ? AND \"superseded_at\" IS NULL";
            this.rowMapper = (rs, rowNum) -> mapRow(rs, type);
        }

        private static String buildPlaceholders(EisObjectType type) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < META_COLUMNS.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('?');
            }
            for (EisObjectField field : type.getFields()) {
                sb.append(", ");
                if (field.getKind() == EisFieldKind.JSONB) {
                    sb.append("CAST(? AS jsonb)");
                } else {
                    sb.append('?');
                }
            }
            return sb.toString();
        }

        private Condition condition(Map<String, Object> criteria) {
            if (criteria == null || criteria.isEmpty()) {
                return new Condition("", new Object[0]);
            }
            List<String> clauses = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> entry : criteria.entrySet()) {
                EisObjectField field = type.getField(entry.getKey());
                if (field == null) {
                    throw new IllegalArgumentException("对象 " + type.getObjectType()
                            + " 未注册字段: " + entry.getKey());
                }
                if (field.getKind() == EisFieldKind.JSONB) {
                    clauses.add(quote(field.getName()) + " = CAST(? AS jsonb)");
                    values.add(entry.getValue() instanceof JsonNode node
                            ? node.toString() : String.valueOf(entry.getValue()));
                } else if (field.getKind() == EisFieldKind.TIMESTAMP) {
                    clauses.add(quote(field.getName()) + " = ?");
                    Object value = entry.getValue();
                    values.add(value instanceof Instant instant
                            ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : value);
                } else {
                    clauses.add(quote(field.getName()) + " = ?");
                    values.add(entry.getValue());
                }
            }
            return new Condition(" AND " + String.join(" AND ", clauses), values.toArray());
        }

        private static EisObjectRow mapRow(ResultSet rs, EisObjectType type) throws SQLException {
            int i = 1;
            UUID id = rs.getObject(i++, UUID.class);
            long version = rs.getLong(i++);
            Instant validFrom = toInstant(rs.getObject(i++, OffsetDateTime.class));
            Instant validTo = toInstant(rs.getObject(i++, OffsetDateTime.class));
            Instant recordedAt = toInstant(rs.getObject(i++, OffsetDateTime.class));
            Instant supersededAt = toInstant(rs.getObject(i++, OffsetDateTime.class));
            UUID createdBy = rs.getObject(i++, UUID.class);
            Instant createdAt = toInstant(rs.getObject(i++, OffsetDateTime.class));
            UUID updatedBy = rs.getObject(i++, UUID.class);
            Instant updatedAt = toInstant(rs.getObject(i++, OffsetDateTime.class));
            Map<String, Object> data = new LinkedHashMap<>();
            for (EisObjectField field : type.getFields()) {
                data.put(field.getName(), readDomain(rs, i++, field.getKind()));
            }
            return new EisObjectRow(type.getObjectType(), id, version, validFrom, validTo, recordedAt,
                    supersededAt, createdBy, createdAt, updatedBy, updatedAt, data);
        }

        private static Instant toInstant(OffsetDateTime value) {
            return value == null ? null : value.toInstant();
        }

        private static String quote(String identifier) {
            return '"' + identifier + '"';
        }
    }

    /**
     * 条件片段（SQL + 绑定值）。
     */
    private static final class Condition {

        private final String sql;

        private final Object[] values;

        private Condition(String sql, Object[] values) {
            this.sql = sql;
            this.values = values;
        }
    }
}
