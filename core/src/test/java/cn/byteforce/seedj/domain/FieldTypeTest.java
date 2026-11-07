package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 字段类型：PostgreSQL 列类型映射与外部值的宽松/失败关闭转换。 */
class FieldTypeTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    @Test
    @DisplayName("每种类型都有稳定的 PostgreSQL 列类型")
    void sqlTypes() {
        assertThat(FieldType.TEXT.sqlType()).isEqualTo("TEXT");
        assertThat(FieldType.INTEGER.sqlType()).isEqualTo("INTEGER");
        assertThat(FieldType.LONG.sqlType()).isEqualTo("BIGINT");
        assertThat(FieldType.DECIMAL.sqlType()).isEqualTo("NUMERIC(18,4)");
        assertThat(FieldType.BOOLEAN.sqlType()).isEqualTo("BOOLEAN");
        assertThat(FieldType.DATE.sqlType()).isEqualTo("DATE");
        assertThat(FieldType.TIMESTAMP.sqlType()).isEqualTo("TIMESTAMPTZ(6)");
        assertThat(FieldType.UUID.sqlType()).isEqualTo("UUID");
    }

    @Test
    @DisplayName("null 原样返回")
    void nullPassesThrough() {
        for (FieldType type : FieldType.values()) {
            assertThat(type.coerce(null)).isNull();
        }
    }

    @Test
    @DisplayName("TEXT 对非字符串值取 String.valueOf")
    void textCoercion() {
        assertThat(FieldType.TEXT.coerce("abc")).isEqualTo("abc");
        assertThat(FieldType.TEXT.coerce(42)).isEqualTo("42");
    }

    @Test
    @DisplayName("INTEGER 接受整数、其它 Number 与带空白的字符串")
    void integerCoercion() {
        assertThat(FieldType.INTEGER.coerce(7)).isEqualTo(7);
        assertThat(FieldType.INTEGER.coerce(7L)).isEqualTo(7);
        assertThat(FieldType.INTEGER.coerce(new BigDecimal("7.9"))).isEqualTo(7);
        assertThat(FieldType.INTEGER.coerce(" 12 ")).isEqualTo(12);
        assertThatThrownBy(() -> FieldType.INTEGER.coerce("twelve"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("invalid integer value: twelve");
                        });
    }

    @Test
    @DisplayName("LONG 接受长整型、其它 Number 与字符串")
    void longCoercion() {
        assertThat(FieldType.LONG.coerce(9L)).isEqualTo(9L);
        assertThat(FieldType.LONG.coerce(9)).isEqualTo(9L);
        assertThat(FieldType.LONG.coerce("9")).isEqualTo(9L);
        assertThatThrownBy(() -> FieldType.LONG.coerce("nine"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("DECIMAL 保留精度，Number 走 toString")
    void decimalCoercion() {
        BigDecimal decimal = new BigDecimal("12.3456");
        assertThat(FieldType.DECIMAL.coerce(decimal)).isSameAs(decimal);
        assertThat(FieldType.DECIMAL.coerce(2.5d)).isEqualTo(new BigDecimal("2.5"));
        assertThat(FieldType.DECIMAL.coerce(" 3.1400 ")).isEqualTo(new BigDecimal("3.1400"));
        assertThatThrownBy(() -> FieldType.DECIMAL.coerce("pi"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("invalid decimal value"));
    }

    @Test
    @DisplayName("BOOLEAN 忽略大小写与空白，其它值失败关闭")
    void booleanCoercion() {
        assertThat(FieldType.BOOLEAN.coerce(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(FieldType.BOOLEAN.coerce("true")).isEqualTo(Boolean.TRUE);
        assertThat(FieldType.BOOLEAN.coerce(" FALSE ")).isEqualTo(Boolean.FALSE);
        assertThatThrownBy(() -> FieldType.BOOLEAN.coerce("yes"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("invalid boolean value: yes");
                        });
    }

    @Test
    @DisplayName("DATE 接受 LocalDate、java.sql.Date 与 ISO 字符串")
    void dateCoercion() {
        LocalDate date = LocalDate.of(2026, 1, 5);
        assertThat(FieldType.DATE.coerce(date)).isSameAs(date);
        assertThat(FieldType.DATE.coerce(java.sql.Date.valueOf(date))).isEqualTo(date);
        assertThat(FieldType.DATE.coerce(" 2026-01-05 ")).isEqualTo(date);
        assertThatThrownBy(() -> FieldType.DATE.coerce("05/01/2026"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.code())
                                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("TIMESTAMP 接受 Instant、OffsetDateTime 与 ISO 字符串")
    void timestampCoercion() {
        Instant instant = Instant.parse("2026-01-05T10:15:30Z");
        assertThat(FieldType.TIMESTAMP.coerce(instant)).isSameAs(instant);
        assertThat(FieldType.TIMESTAMP.coerce(OffsetDateTime.parse("2026-01-05T11:15:30+01:00")))
                .isEqualTo(instant);
        assertThat(FieldType.TIMESTAMP.coerce("2026-01-05T10:15:30Z")).isEqualTo(instant);
        assertThatThrownBy(() -> FieldType.TIMESTAMP.coerce("tomorrow"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("invalid timestamp value"));
    }

    @Test
    @DisplayName("UUID 接受 UUID 与字符串")
    void uuidCoercion() {
        assertThat(FieldType.UUID.coerce(ID)).isSameAs(ID);
        assertThat(FieldType.UUID.coerce(" " + ID + " ")).isEqualTo(ID);
        assertThatThrownBy(() -> FieldType.UUID.coerce("not-a-uuid"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("invalid uuid value"));
    }
}
