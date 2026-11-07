package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ScopePredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把受限谓词编译成参数化 SQL 片段。
 *
 * <p>字段白名单是表的全部领域列（不是 {@code filterableColumns}）：行范围允许使用 未开放给业务过滤的字段，但仍必须是 schema 声明过的字段。{@code
 * Eq/In} 先按 {@link FieldType} 转换，再转成 JDBC 值，参数顺序与占位符一致。
 *
 * <p>{@code Not} 编译为 {@code (p) IS NOT TRUE}，保持二值语义：字段为 {@code NULL} 时取反仍为真。
 *
 * @author ByteForce
 * @since 1.0.0
 */
final class ScopeSql {

    private ScopeSql() {}

    /**
     * 编译结果。
     *
     * @param sql 不含外层括号的 SQL 片段
     * @param args 与占位符顺序一致的 JDBC 参数
     */
    record Clause(String sql, List<Object> args) {

        Clause {
            Objects.requireNonNull(sql, "sql");
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /**
     * 编译谓词。
     *
     * @param predicate 谓词
     * @param table 目标表
     * @return SQL 片段与参数
     */
    static Clause compile(ScopePredicate predicate, TemporalTable table) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(table, "table");
        return switch (predicate) {
            case ScopePredicate.All ignored -> new Clause("TRUE", List.of());
            case ScopePredicate.None ignored -> new Clause("FALSE", List.of());
            case ScopePredicate.Eq eq -> eq(eq, table);
            case ScopePredicate.In in -> in(in, table);
            case ScopePredicate.NotNull notNull -> notNull(notNull, table);
            case ScopePredicate.And and -> join(and.parts(), table, " AND ", "TRUE");
            case ScopePredicate.Or or -> join(or.parts(), table, " OR ", "FALSE");
            case ScopePredicate.Not not -> {
                Clause inner = compile(not.part(), table);
                yield new Clause("(" + inner.sql() + ") IS NOT TRUE", inner.args());
            }
        };
    }

    private static Clause eq(ScopePredicate.Eq eq, TemporalTable table) {
        requireField(eq.field(), table);
        if (eq.value() == null) {
            return new Clause("FALSE", List.of());
        }
        return new Clause(eq.field() + " = ?", List.of(jdbcValue(eq.field(), eq.value(), table)));
    }

    private static Clause in(ScopePredicate.In in, TemporalTable table) {
        requireField(in.field(), table);
        if (in.values().isEmpty()) {
            return new Clause("FALSE", List.of());
        }
        List<Object> args = new ArrayList<>(in.values().size());
        for (Object value : in.values()) {
            args.add(jdbcValue(in.field(), value, table));
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(args.size(), "?"));
        return new Clause(in.field() + " IN (" + placeholders + ")", args);
    }

    private static Clause notNull(ScopePredicate.NotNull notNull, TemporalTable table) {
        requireField(notNull.field(), table);
        return new Clause(notNull.field() + " IS NOT NULL", List.of());
    }

    private static Clause join(
            List<ScopePredicate> parts, TemporalTable table, String separator, String identity) {
        if (parts.isEmpty()) {
            return new Clause(identity, List.of());
        }
        if (parts.size() == 1) {
            return compile(parts.get(0), table);
        }
        List<String> sql = new ArrayList<>(parts.size());
        List<Object> args = new ArrayList<>();
        for (ScopePredicate part : parts) {
            Clause clause = compile(part, table);
            sql.add(clause.sql());
            args.addAll(clause.args());
        }
        return new Clause("(" + String.join(separator, sql) + ")", args);
    }

    private static void requireField(String field, TemporalTable table) {
        if (!table.columns().containsKey(field)) {
            throw new IllegalArgumentException("unknown scope field: " + field);
        }
    }

    private static Object jdbcValue(String field, Object value, TemporalTable table) {
        FieldType type = table.fieldTypes().get(field);
        if (type == null) {
            return value;
        }
        return TemporalValues.toJdbc(type, type.coerce(value));
    }
}
