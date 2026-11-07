package cn.byteforce.seedj.infra.jdbc.domain;

import cn.byteforce.seedj.domain.FieldType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 业务字段类型 ↔ JDBC 值的转换。
 *
 * <p>读回值必须先经过类型转换再交给调用方：不同 JDBC 驱动对 {@code DATE}/{@code TIMESTAMPTZ} 可能返回 {@link
 * java.sql.Date}/{@link Timestamp} 或 {@link LocalDate}/{@link Instant}， 不能假设某一种。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class TemporalValues {

    private TemporalValues() {}

    /**
     * 写库前转换。
     *
     * @param type 字段类型；为空表示按原值写入
     * @param value 类型化值
     * @return JDBC 值
     */
    public static Object toJdbc(FieldType type, Object value) {
        if (value == null || type == null) {
            return value;
        }
        return switch (type) {
            case DATE -> value instanceof LocalDate date ? java.sql.Date.valueOf(date) : value;
            case TIMESTAMP -> value instanceof Instant instant ? Timestamp.from(instant) : value;
            default -> value;
        };
    }

    /**
     * 读库后转换。
     *
     * @param type 字段类型；为空表示按原值返回
     * @param value JDBC 值
     * @return 类型化值
     */
    public static Object fromJdbc(FieldType type, Object value) {
        if (value == null || type == null) {
            return value;
        }
        return switch (type) {
            case TEXT -> value instanceof String ? value : String.valueOf(value);
            case INTEGER -> value instanceof Number number ? number.intValue() : type.coerce(value);
            case LONG -> value instanceof Number number ? number.longValue() : type.coerce(value);
            case DECIMAL -> value instanceof BigDecimal decimal ? decimal : type.coerce(value);
            case BOOLEAN -> value instanceof Boolean bool ? bool : type.coerce(value);
            case DATE ->
                    switch (value) {
                        case java.sql.Date date -> date.toLocalDate();
                        case LocalDate date -> date;
                        default -> type.coerce(value);
                    };
            case TIMESTAMP ->
                    switch (value) {
                        case Timestamp timestamp -> timestamp.toInstant();
                        case Instant instant -> instant;
                        default -> type.coerce(value);
                    };
            case UUID -> value instanceof UUID uuid ? uuid : type.coerce(value);
        };
    }
}
