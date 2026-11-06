package cn.byteforce.seedj.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * {@link ScopePredicate} 的求值与合成工具。
 *
 * <p>合成遵循“只能收窄”：{@code and} 以 {@link ScopePredicate.All} 为单位元、
 * {@link ScopePredicate.None} 为吸收元，{@code or} 反之。子谓词扁平化并用
 * {@link LinkedHashSet} 去重，保持首次出现顺序。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ScopePredicates {

    /** 单个 {@code In} 允许的常量上限，避免生成巨型 SQL。 */
    public static final int MAX_IN_VALUES = 1000;

    private ScopePredicates() {
    }

    /**
     * 在内存中求值：字段按 schema 声明类型转换后比较。
     *
     * <p>{@code Not} 使用二值逻辑，字段为 {@code null} 时 {@code Not(Eq(...))} 为真。
     * 未知字段抛 {@link IllegalArgumentException}，不静默当作不匹配。</p>
     *
     * @param row       待判定行
     * @param predicate 谓词
     * @param schema    对象 schema
     * @return 命中为 {@code true}
     */
    public static boolean matches(TemporalRow row, ScopePredicate predicate,
                                  ObjectSchema schema) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(schema, "schema");
        return switch (predicate) {
            case ScopePredicate.All ignored -> true;
            case ScopePredicate.None ignored -> false;
            case ScopePredicate.Eq eq -> matchesEq(row, eq, schema);
            case ScopePredicate.In in -> matchesIn(row, in, schema);
            case ScopePredicate.NotNull notNull -> matchesNotNull(row, notNull, schema);
            case ScopePredicate.And and -> and.parts().stream()
                    .allMatch(part -> matches(row, part, schema));
            case ScopePredicate.Or or -> or.parts().stream()
                    .anyMatch(part -> matches(row, part, schema));
            case ScopePredicate.Not not -> !matches(row, not.part(), schema);
        };
    }

    private static boolean matchesEq(TemporalRow row, ScopePredicate.Eq eq,
                                     ObjectSchema schema) {
        if (eq.value() == null) {
            return false;
        }
        Object expected = schema.requireField(eq.field()).type().coerce(eq.value());
        return Objects.equals(row.fields().get(eq.field()), expected);
    }

    private static boolean matchesIn(TemporalRow row, ScopePredicate.In in,
                                     ObjectSchema schema) {
        FieldType type = schema.requireField(in.field()).type();
        Object actual = row.fields().get(in.field());
        if (actual == null || in.values().isEmpty()) {
            return false;
        }
        return in.values().stream().map(type::coerce).anyMatch(actual::equals);
    }

    private static boolean matchesNotNull(TemporalRow row, ScopePredicate.NotNull notNull,
                                          ObjectSchema schema) {
        schema.requireField(notNull.field());
        return row.fields().get(notNull.field()) != null;
    }

    /**
     * 合成“全部满足”：扁平化、去重、化简。
     *
     * @param parts 子谓词
     * @return 谓词
     */
    public static ScopePredicate and(List<ScopePredicate> parts) {
        Objects.requireNonNull(parts, "parts");
        LinkedHashSet<ScopePredicate> flat = new LinkedHashSet<>();
        for (ScopePredicate part : parts) {
            if (!collectAnd(part, flat)) {
                return new ScopePredicate.None();
            }
        }
        return combine(flat, true);
    }

    /**
     * 合成“任一满足”：扁平化、去重、化简。
     *
     * @param parts 子谓词
     * @return 谓词
     */
    public static ScopePredicate or(List<ScopePredicate> parts) {
        Objects.requireNonNull(parts, "parts");
        LinkedHashSet<ScopePredicate> flat = new LinkedHashSet<>();
        for (ScopePredicate part : parts) {
            if (!collectOr(part, flat)) {
                return new ScopePredicate.All();
            }
        }
        return combine(flat, false);
    }

    private static boolean collectAnd(ScopePredicate part, LinkedHashSet<ScopePredicate> flat) {
        Objects.requireNonNull(part, "part");
        if (part instanceof ScopePredicate.None) {
            return false;
        }
        if (part instanceof ScopePredicate.All) {
            return true;
        }
        if (part instanceof ScopePredicate.And and) {
            for (ScopePredicate nested : and.parts()) {
                if (!collectAnd(nested, flat)) {
                    return false;
                }
            }
            return true;
        }
        flat.add(part);
        return true;
    }

    private static boolean collectOr(ScopePredicate part, LinkedHashSet<ScopePredicate> flat) {
        Objects.requireNonNull(part, "part");
        if (part instanceof ScopePredicate.All) {
            return false;
        }
        if (part instanceof ScopePredicate.None) {
            return true;
        }
        if (part instanceof ScopePredicate.Or or) {
            for (ScopePredicate nested : or.parts()) {
                if (!collectOr(nested, flat)) {
                    return false;
                }
            }
            return true;
        }
        flat.add(part);
        return true;
    }

    private static ScopePredicate combine(LinkedHashSet<ScopePredicate> flat, boolean and) {
        if (flat.isEmpty()) {
            return and ? new ScopePredicate.All() : new ScopePredicate.None();
        }
        if (flat.size() == 1) {
            return flat.iterator().next();
        }
        List<ScopePredicate> parts = List.copyOf(flat);
        return and ? new ScopePredicate.And(parts) : new ScopePredicate.Or(parts);
    }
}
