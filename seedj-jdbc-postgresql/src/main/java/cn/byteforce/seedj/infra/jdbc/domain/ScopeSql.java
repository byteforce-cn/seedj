package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 把受限谓词编译成参数化 SQL 片段：行范围的唯一编译器。
 *
 * <p>字段白名单是表的全部领域列（不是 {@code filterableColumns}）：行范围允许使用 未开放给业务过滤的字段，但仍必须是 schema 声明过的字段。{@code
 * Eq/In} 先按 {@link FieldType} 转换，再转成 JDBC 值，参数顺序与占位符一致。
 *
 * <p>{@code Not} 编译为 {@code (p) IS NOT TRUE}，保持二值语义：字段为 {@code NULL} 时取反仍为真。 内存实现的 {@code
 * ScopePredicates} 使用同一语义（{@code null} 不匹配 {@code Eq}，取反为真）。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopeSql {

    private ScopeSql() {}

    /**
     * 编译结果。
     *
     * @param sql 不含外层括号的 SQL 片段
     * @param args 与占位符顺序一致的 JDBC 参数
     */
    public record Clause(String sql, List<Object> args) {

        public Clause {
            Objects.requireNonNull(sql, "sql");
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /**
     * 为对象表编译谓词：列名是裸列名。
     *
     * @param predicate 谓词
     * @param table 目标表
     * @return SQL 片段与参数
     */
    public static Clause compile(ScopePredicate predicate, TemporalTable table) {
        Objects.requireNonNull(table, "table");
        return compile(
                predicate,
                table.columns().keySet()::contains,
                field -> table.fieldTypes().get(field),
                UnaryOperator.identity(),
                field -> "unknown scope field: " + field);
    }

    /**
     * 为自定义查询编译谓词：列名限定为 {@code seedj_q."field"}，字段必须出现在查询投影里。
     *
     * @param predicate 谓词
     * @param schema 对象 schema（字段类型）
     * @param projected 查询投影的字段集合
     * @return SQL 片段与参数
     */
    public static Clause compile(
            ScopePredicate predicate, ObjectSchema schema, Set<String> projected) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(projected, "projected");
        return compile(
                predicate,
                projected::contains,
                field -> schema.requireField(field).type(),
                field -> "seedj_q.\"" + field + "\"",
                field -> "row scope uses field " + field + " but the query does not project it");
    }

    private static Clause compile(
            ScopePredicate predicate,
            Predicate<String> known,
            Function<String, FieldType> type,
            UnaryOperator<String> column,
            Function<String, String> unknownFieldMessage) {
        Objects.requireNonNull(predicate, "predicate");
        return switch (predicate) {
            case ScopePredicate.All ignored -> new Clause("TRUE", List.of());
            case ScopePredicate.None ignored -> new Clause("FALSE", List.of());
            case ScopePredicate.Eq eq -> eq(eq, known, type, column, unknownFieldMessage);
            case ScopePredicate.In in -> in(in, known, type, column, unknownFieldMessage);
            case ScopePredicate.NotNull notNull ->
                    notNull(notNull, known, column, unknownFieldMessage);
            case ScopePredicate.And and ->
                    join(and.parts(), known, type, column, unknownFieldMessage, " AND ", "TRUE");
            case ScopePredicate.Or or ->
                    join(or.parts(), known, type, column, unknownFieldMessage, " OR ", "FALSE");
            case ScopePredicate.Not not -> {
                Clause inner = compile(not.part(), known, type, column, unknownFieldMessage);
                yield new Clause("(" + inner.sql() + ") IS NOT TRUE", inner.args());
            }
        };
    }

    private static Clause eq(
            ScopePredicate.Eq eq,
            Predicate<String> known,
            Function<String, FieldType> type,
            UnaryOperator<String> column,
            Function<String, String> unknownFieldMessage) {
        requireField(eq.field(), known, unknownFieldMessage);
        if (eq.value() == null) {
            return new Clause("FALSE", List.of());
        }
        return new Clause(
                column.apply(eq.field()) + " = ?",
                List.of(jdbcValue(type.apply(eq.field()), eq.value())));
    }

    private static Clause in(
            ScopePredicate.In in,
            Predicate<String> known,
            Function<String, FieldType> type,
            UnaryOperator<String> column,
            Function<String, String> unknownFieldMessage) {
        requireField(in.field(), known, unknownFieldMessage);
        if (in.values().isEmpty()) {
            return new Clause("FALSE", List.of());
        }
        List<Object> args = new ArrayList<>(in.values().size());
        for (Object value : in.values()) {
            args.add(jdbcValue(type.apply(in.field()), value));
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(args.size(), "?"));
        return new Clause(column.apply(in.field()) + " IN (" + placeholders + ")", args);
    }

    private static Clause notNull(
            ScopePredicate.NotNull notNull,
            Predicate<String> known,
            UnaryOperator<String> column,
            Function<String, String> unknownFieldMessage) {
        requireField(notNull.field(), known, unknownFieldMessage);
        return new Clause(column.apply(notNull.field()) + " IS NOT NULL", List.of());
    }

    private static Clause join(
            List<ScopePredicate> parts,
            Predicate<String> known,
            Function<String, FieldType> type,
            UnaryOperator<String> column,
            Function<String, String> unknownFieldMessage,
            String separator,
            String identity) {
        if (parts.isEmpty()) {
            return new Clause(identity, List.of());
        }
        if (parts.size() == 1) {
            return compile(parts.get(0), known, type, column, unknownFieldMessage);
        }
        List<String> sql = new ArrayList<>(parts.size());
        List<Object> args = new ArrayList<>();
        for (ScopePredicate part : parts) {
            Clause clause = compile(part, known, type, column, unknownFieldMessage);
            sql.add(clause.sql());
            args.addAll(clause.args());
        }
        return new Clause("(" + String.join(separator, sql) + ")", args);
    }

    private static void requireField(
            String field, Predicate<String> known, Function<String, String> unknownFieldMessage) {
        if (!known.test(field)) {
            throw new IllegalArgumentException(unknownFieldMessage.apply(field));
        }
    }

    private static Object jdbcValue(FieldType type, Object value) {
        if (type == null) {
            return value;
        }
        return TemporalValues.toJdbc(type, type.coerce(value));
    }
}
