package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 业务字段类型：对象 schema、HTTP 过滤参数、JDBC 读写共用同一套转换。
 *
 * <p>HTTP 传来的过滤值总是字符串；这里把字符串按声明类型转换，非法值统一抛
 * {@link ContractException}（无效载荷），避免落到 SQL 层变成 500。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public enum FieldType {

    TEXT("TEXT"),
    INTEGER("INTEGER"),
    LONG("BIGINT"),
    DECIMAL("NUMERIC(18,4)"),
    BOOLEAN("BOOLEAN"),
    DATE("DATE"),
    TIMESTAMP("TIMESTAMPTZ(6)"),
    UUID("UUID");

    private final String sqlType;

    FieldType(String sqlType) {
        this.sqlType = sqlType;
    }

    /**
     * 对应的 PostgreSQL 列类型。
     *
     * @return SQL 类型
     */
    public String sqlType() {
        return sqlType;
    }

    /**
     * 按字段声明的精度/标度给出列类型。
     *
     * <p>DECIMAL 必须显式声明 precision/scale；本方法只做渲染，无法判断声明缺失。
     * 其他类型忽略精度参数。未声明精度的 DECIMAL 保留历史默认值，便于兼容旧装配；
     * 代码生成器会在编译期拒绝这种声明。</p>
     *
     * @param precision 总位数；{@code null} 表示未声明
     * @param scale     小数位数；{@code null} 表示 0
     * @return SQL 类型
     */
    public String sqlType(Integer precision, Integer scale) {
        if (this != DECIMAL || precision == null) {
            return sqlType;
        }
        if (precision < 1) {
            throw new IllegalArgumentException("decimal precision must be >= 1");
        }
        int digits = scale == null ? 0 : scale;
        if (digits < 0 || digits > precision) {
            throw new IllegalArgumentException(
                    "decimal scale must be between 0 and " + precision);
        }
        return "NUMERIC(" + precision + "," + digits + ")";
    }

    /**
     * 把外部值转换为本类型。
     *
     * @param value 原始值（HTTP 字符串或已类型化值）
     * @return 类型化值；{@code null} 原样返回
     */
    public Object coerce(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return switch (this) {
                case TEXT -> value instanceof String ? value : String.valueOf(value);
                case INTEGER -> toInteger(value);
                case LONG -> toLong(value);
                case DECIMAL -> toDecimal(value);
                case BOOLEAN -> toBoolean(value);
                case DATE -> toDate(value);
                case TIMESTAMP -> toInstant(value);
                case UUID -> toUuid(value);
            };
        } catch (ContractException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid(value);
        }
    }

    private ContractException invalid(Object value) {
        return new ContractException(ContractErrorCode.INVALID_PAYLOAD,
                "invalid " + name().toLowerCase() + " value: " + value);
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString().trim());
    }

    private Long toLong(Object value) {
        if (value instanceof Long longer) {
            return longer;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString().trim());
    }

    private BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString().trim());
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("not a boolean: " + text);
    }

    private LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(value.toString().trim());
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        return OffsetDateTime.parse(value.toString().trim()).toInstant();
    }

    private java.util.UUID toUuid(Object value) {
        if (value instanceof java.util.UUID uuid) {
            return uuid;
        }
        return java.util.UUID.fromString(value.toString().trim());
    }
}
