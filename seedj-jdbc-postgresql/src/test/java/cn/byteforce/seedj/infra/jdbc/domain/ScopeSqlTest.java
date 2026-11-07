package cn.byteforce.seedj.infra.jdbc.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopeSqlTest {

    private static TemporalTable table() {
        ObjectSchema schema =
                ObjectSchema.builder("probe", "probe_object")
                        .field("org_unit_id", FieldType.TEXT)
                        .field("status", FieldType.TEXT)
                        .field("salary", FieldType.DECIMAL)
                        .field("hire_date", FieldType.DATE)
                        .build();
        return TemporalTable.from(schema);
    }

    @Test
    void eqUsesParameterizedValue() {
        var clause = ScopeSql.compile(ScopePredicate.eq("org_unit_id", "D01"), table());

        assertThat(clause.sql()).isEqualTo("org_unit_id = ?");
        assertThat(clause.args()).containsExactly("D01");
    }

    @Test
    void inUsesPlaceholdersAndCoerces() {
        var clause = ScopeSql.compile(ScopePredicate.in("salary", List.of("1.5", "2.5")), table());

        assertThat(clause.sql()).isEqualTo("salary IN (?, ?)");
        assertThat(clause.args()).containsExactly(new BigDecimal("1.5"), new BigDecimal("2.5"));
    }

    @Test
    void dateIsCoercedToJdbcValue() {
        var clause = ScopeSql.compile(ScopePredicate.eq("hire_date", "2026-01-05"), table());

        assertThat(clause.args()).containsExactly(java.sql.Date.valueOf(LocalDate.of(2026, 1, 5)));
    }

    @Test
    void notUsesIsNotTrue() {
        var clause =
                ScopeSql.compile(
                        ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D01")), table());

        assertThat(clause.sql()).isEqualTo("(org_unit_id = ?) IS NOT TRUE");
        assertThat(clause.args()).containsExactly("D01");
    }

    @Test
    void allAndNoneBecomeConstants() {
        assertThat(ScopeSql.compile(ScopePredicate.all(), table()).sql()).isEqualTo("TRUE");
        assertThat(ScopeSql.compile(ScopePredicate.none(), table()).sql()).isEqualTo("FALSE");
        assertThat(ScopeSql.compile(ScopePredicate.all(), table()).args()).isEmpty();
    }

    @Test
    void unknownFieldIsRejected() {
        assertThatThrownBy(() -> ScopeSql.compile(ScopePredicate.eq("missing", "x"), table()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> ScopeSql.compile(ScopePredicate.notNull("missing"), table()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void notNullBecomesIsNotNull() {
        assertThat(ScopeSql.compile(ScopePredicate.notNull("org_unit_id"), table()).sql())
                .isEqualTo("org_unit_id IS NOT NULL");
    }

    @Test
    void andOrUseParentheses() {
        var and =
                ScopeSql.compile(
                        ScopePredicate.and(
                                ScopePredicate.eq("status", "ACTIVE"),
                                ScopePredicate.eq("org_unit_id", "D01")),
                        table());
        assertThat(and.sql()).isEqualTo("(status = ? AND org_unit_id = ?)");
        assertThat(and.args()).containsExactly("ACTIVE", "D01");

        var or =
                ScopeSql.compile(
                        ScopePredicate.or(
                                ScopePredicate.eq("status", "ACTIVE"),
                                ScopePredicate.eq("status", "PENDING")),
                        table());
        assertThat(or.sql()).isEqualTo("(status = ? OR status = ?)");
    }

    @Test
    void emptyInBecomesFalse() {
        var clause = ScopeSql.compile(new ScopePredicate.In("status", List.of()), table());

        assertThat(clause.sql()).isEqualTo("FALSE");
        assertThat(clause.args()).isEmpty();
    }
}
