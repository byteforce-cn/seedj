package cn.byteforce.seedj.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 受限行范围谓词 AST：业务范围与策略引擎共同贡献、只能收窄。
 *
 * <p>字段必须是 {@link ObjectSchema} 声明过的字段；{@code Eq/In} 的值在求值与 SQL 编译时按 {@link FieldType} 转换。{@code
 * Not} 是二值逻辑：{@code Not(p)} 为真当且 仅当 {@code p} 为假，字段为 {@code null} 时也成立。
 *
 * <p>构造期做归一化：{@code Eq(null)} 与空 {@code In} 变成 {@link None}； {@code In} 丢弃 {@code null} 元素且上限
 * 1000；{@code and/or} 扁平化、去重并按 布尔代数化简。直接构造 record 时不做化简，只校验结构。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public sealed interface ScopePredicate {

    /** 允许全部行（只允许业务显式返回，框架不提供默认值）。 */
    record All() implements ScopePredicate {}

    /** 不允许任何行。 */
    record None() implements ScopePredicate {}

    /**
     * 字段等于常量。
     *
     * @param field 字段名
     * @param value 常量；{@code null} 表示无匹配
     */
    record Eq(String field, Object value) implements ScopePredicate {
        public Eq {
            Fields.require(field);
        }
    }

    /**
     * 字段属于常量集合。
     *
     * @param field 字段名
     * @param values 常量集合（不可变、已去 null、最多 1000 个）
     */
    record In(String field, List<?> values) implements ScopePredicate {
        public In {
            Fields.require(field);
            Objects.requireNonNull(values, "values");
            values = values.stream().filter(Objects::nonNull).toList();
            if (values.size() > ScopePredicates.MAX_IN_VALUES) {
                throw new IllegalArgumentException(
                        "in predicate supports at most "
                                + ScopePredicates.MAX_IN_VALUES
                                + " values, got: "
                                + values.size());
            }
        }
    }

    /**
     * 字段非空。
     *
     * @param field 字段名
     */
    record NotNull(String field) implements ScopePredicate {
        public NotNull {
            Fields.require(field);
        }
    }

    /**
     * 全部满足。
     *
     * @param parts 子谓词；空表示 {@link All}
     */
    record And(List<ScopePredicate> parts) implements ScopePredicate {
        public And {
            parts = Parts.copy(parts);
        }
    }

    /**
     * 任一满足。
     *
     * @param parts 子谓词；空表示 {@link None}
     */
    record Or(List<ScopePredicate> parts) implements ScopePredicate {
        public Or {
            parts = Parts.copy(parts);
        }
    }

    /**
     * 取反（二值逻辑）。
     *
     * @param part 子谓词
     */
    record Not(ScopePredicate part) implements ScopePredicate {
        public Not {
            Objects.requireNonNull(part, "part");
        }
    }

    /**
     * 全部行。
     *
     * @return 单位元
     */
    static ScopePredicate all() {
        return new All();
    }

    /**
     * 空集。
     *
     * @return 吸收元
     */
    static ScopePredicate none() {
        return new None();
    }

    /**
     * 字段等于常量；常量 {@code null} 归一为 {@link #none()}。
     *
     * @param field 字段名
     * @param value 常量
     * @return 谓词
     */
    static ScopePredicate eq(String field, Object value) {
        return value == null ? none() : new Eq(field, value);
    }

    /**
     * 字段属于集合；丢弃 {@code null}，空集合归一为 {@link #none()}。
     *
     * @param field 字段名
     * @param values 常量集合
     * @return 谓词
     */
    static ScopePredicate in(String field, Collection<?> values) {
        Objects.requireNonNull(values, "values");
        In predicate = new In(field, new java.util.ArrayList<>(values));
        return predicate.values().isEmpty() ? none() : predicate;
    }

    /**
     * 字段非空。
     *
     * @param field 字段名
     * @return 谓词
     */
    static ScopePredicate notNull(String field) {
        return new NotNull(field);
    }

    /**
     * 全部满足；扁平化、去重并化简。
     *
     * @param parts 子谓词
     * @return 谓词
     */
    static ScopePredicate and(ScopePredicate... parts) {
        return ScopePredicates.and(Arrays.asList(parts));
    }

    /**
     * 任一满足；扁平化、去重并化简。
     *
     * @param parts 子谓词
     * @return 谓词
     */
    static ScopePredicate or(ScopePredicate... parts) {
        return ScopePredicates.or(Arrays.asList(parts));
    }

    /**
     * 取反；双重否定与常量取反直接化简。
     *
     * @param part 子谓词
     * @return 谓词
     */
    static ScopePredicate not(ScopePredicate part) {
        Objects.requireNonNull(part, "part");
        if (part instanceof All) {
            return none();
        }
        if (part instanceof None) {
            return all();
        }
        if (part instanceof Not not) {
            return not.part();
        }
        return new Not(part);
    }

    /** 字段名与子谓词列表的共享校验。 */
    final class Fields {

        private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]*$");

        private Fields() {}

        static String require(String field) {
            if (field == null || !IDENTIFIER.matcher(field).matches()) {
                throw new IllegalArgumentException("illegal field name: " + field);
            }
            return field;
        }
    }

    /** 子谓词列表的共享防御性复制。 */
    final class Parts {

        private Parts() {}

        static List<ScopePredicate> copy(List<ScopePredicate> parts) {
            Objects.requireNonNull(parts, "parts");
            for (ScopePredicate part : parts) {
                Objects.requireNonNull(part, "part");
            }
            return List.copyOf(parts);
        }
    }
}
