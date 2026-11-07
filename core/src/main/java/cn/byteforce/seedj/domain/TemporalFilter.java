package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 受限列表过滤器 AST：只允许 schema 声明的可过滤字段与参数化值。
 *
 * <p>存储实现负责按 {@link FieldType} 转换值并生成占位符；未知/不可过滤字段
 * 一律抛无效载荷，不拼接进 SQL。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public sealed interface TemporalFilter {

    /** 字段名格式。 */
    Pattern FIELD = Pattern.compile("^[a-z][a-z0-9_]*$");

    /**
     * 等于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Eq(String field, Object value) implements TemporalFilter {
        public Eq {
            requireField(field);
        }
    }

    /**
     * 不等于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Ne(String field, Object value) implements TemporalFilter {
        public Ne {
            requireField(field);
        }
    }

    /**
     * 属于集合。
     *
     * @param field  字段名
     * @param values 值集合
     */
    record In(String field, List<?> values) implements TemporalFilter {
        public In {
            requireField(field);
            Objects.requireNonNull(values, "values");
            values = values.stream().filter(Objects::nonNull).toList();
            if (values.size() > ScopePredicates.MAX_IN_VALUES) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "in filter supports at most " + ScopePredicates.MAX_IN_VALUES
                                + " values, got: " + values.size());
            }
        }
    }

    /**
     * 大于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Gt(String field, Object value) implements TemporalFilter {
        public Gt {
            requireField(field);
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 大于等于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Gte(String field, Object value) implements TemporalFilter {
        public Gte {
            requireField(field);
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 小于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Lt(String field, Object value) implements TemporalFilter {
        public Lt {
            requireField(field);
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 小于等于。
     *
     * @param field 字段名
     * @param value 值
     */
    record Lte(String field, Object value) implements TemporalFilter {
        public Lte {
            requireField(field);
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 闭区间。
     *
     * @param field 字段名
     * @param lower 下界
     * @param upper 上界
     */
    record Between(String field, Object lower, Object upper) implements TemporalFilter {
        public Between {
            requireField(field);
            Objects.requireNonNull(lower, "lower");
            Objects.requireNonNull(upper, "upper");
        }
    }

    /**
     * SQL LIKE 模式（{@code %} / {@code _}）。
     *
     * @param field   字段名
     * @param pattern 模式
     */
    record Like(String field, String pattern) implements TemporalFilter {
        public Like {
            requireField(field);
            if (pattern == null || pattern.isBlank()) {
                throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                        "like filter pattern is required");
            }
        }
    }

    /**
     * 全部满足。
     *
     * @param parts 子过滤器
     */
    record And(List<TemporalFilter> parts) implements TemporalFilter {
        public And {
            Objects.requireNonNull(parts, "parts");
            for (TemporalFilter part : parts) {
                Objects.requireNonNull(part, "part");
            }
            parts = List.copyOf(parts);
        }
    }

    /**
     * 任一满足。
     *
     * @param parts 子过滤器
     */
    record Or(List<TemporalFilter> parts) implements TemporalFilter {
        public Or {
            Objects.requireNonNull(parts, "parts");
            for (TemporalFilter part : parts) {
                Objects.requireNonNull(part, "part");
            }
            parts = List.copyOf(parts);
        }
    }

    /**
     * 取反。
     *
     * @param part 子过滤器
     */
    record Not(TemporalFilter part) implements TemporalFilter {
        public Not {
            Objects.requireNonNull(part, "part");
        }
    }

    private static void requireField(String field) {
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                    "illegal filter field: " + field);
        }
    }
}
