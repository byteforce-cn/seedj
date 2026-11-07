package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ScopePredicateTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static ObjectSchema schema() {
        return ObjectSchema.builder("employee", "hr_employee")
                .required("employee_no", FieldType.TEXT)
                .field("hire_date", FieldType.DATE)
                .field("org_unit_id", FieldType.TEXT)
                .field("salary", FieldType.DECIMAL)
                .build();
    }

    private static TemporalRow row(Map<String, Object> fields) {
        return new TemporalRow("employee", version(), fields);
    }

    private static ObjectVersion version() {
        Instant now = Instant.parse("2026-11-06T09:00:00Z");
        return new ObjectVersion(ID, 1, now, null, now, null, ID, now, null, null);
    }

    @Test
    void eqMatchesCoercedValue() {
        var row = row(Map.of("employee_no", "E-1", "hire_date", LocalDate.of(2026, 1, 5)));

        assertThat(
                        ScopePredicates.matches(
                                row, ScopePredicate.eq("hire_date", "2026-01-05"), schema()))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row, ScopePredicate.eq("hire_date", "2026-01-06"), schema()))
                .isFalse();
    }

    @Test
    void eqWithNullValueBecomesNone() {
        assertThat(ScopePredicate.eq("org_unit_id", null)).isEqualTo(ScopePredicate.none());
    }

    @Test
    void notMatchesNullField() {
        var row = row(Map.of("employee_no", "E-1"));

        assertThat(ScopePredicates.matches(row, ScopePredicate.eq("org_unit_id", "D01"), schema()))
                .isFalse();
        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D01")),
                                schema()))
                .isTrue();
    }

    @Test
    void inRejectsMoreThanOneThousandValues() {
        assertThatThrownBy(
                        () ->
                                ScopePredicate.in(
                                        "employee_no",
                                        IntStream.range(0, 1001).mapToObj(i -> "E-" + i).toList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void inDropsNullsAndEmptyBecomesNone() {
        assertThat(ScopePredicate.in("employee_no", Arrays.asList("E-1", null, "E-2")))
                .isEqualTo(ScopePredicate.in("employee_no", List.of("E-1", "E-2")));
        assertThat(ScopePredicate.in("employee_no", new ArrayList<>()))
                .isEqualTo(ScopePredicate.none());
        assertThat(ScopePredicate.in("employee_no", Arrays.asList(null, null)))
                .isEqualTo(ScopePredicate.none());
    }

    @Test
    void inMatchesCoercedValues() {
        var row = row(Map.of("employee_no", "E-2"));

        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.in("employee_no", List.of("E-1", "E-2")),
                                schema()))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row, ScopePredicate.in("employee_no", List.of("E-3")), schema()))
                .isFalse();
    }

    @Test
    void notNullMatchesRowsWithValue() {
        var present = row(Map.of("employee_no", "E-1", "org_unit_id", "D01"));
        var absent = row(Map.of("employee_no", "E-1"));

        assertThat(
                        ScopePredicates.matches(
                                present, ScopePredicate.notNull("org_unit_id"), schema()))
                .isTrue();
        assertThat(ScopePredicates.matches(absent, ScopePredicate.notNull("org_unit_id"), schema()))
                .isFalse();
    }

    @Test
    void unknownFieldFailsFast() {
        var row = row(Map.of("employee_no", "E-1"));

        assertThatThrownBy(
                        () ->
                                ScopePredicates.matches(
                                        row, ScopePredicate.eq("missing", "x"), schema()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(
                        () ->
                                ScopePredicates.matches(
                                        row, ScopePredicate.notNull("missing"), schema()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void illegalFieldNameFailsAtConstruction() {
        assertThatThrownBy(() -> ScopePredicate.eq("Org Unit", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compositionFlattensAndShortCircuits() {
        var a = ScopePredicate.eq("employee_no", "E-1");
        var b = ScopePredicate.eq("org_unit_id", "D01");

        assertThat(ScopePredicate.and()).isEqualTo(ScopePredicate.all());
        assertThat(ScopePredicate.or()).isEqualTo(ScopePredicate.none());
        assertThat(ScopePredicate.and(a, ScopePredicate.all(), ScopePredicate.and(b)))
                .isEqualTo(ScopePredicates.and(List.of(a, b)));
        assertThat(ScopePredicate.and(a, ScopePredicate.none())).isEqualTo(ScopePredicate.none());
        assertThat(ScopePredicate.or(a, ScopePredicate.none(), ScopePredicate.or(b)))
                .isEqualTo(ScopePredicates.or(List.of(a, b)));
        assertThat(ScopePredicate.or(a, ScopePredicate.all())).isEqualTo(ScopePredicate.all());
        assertThat(ScopePredicate.and(a, a)).isEqualTo(a);
        assertThat(ScopePredicate.or(a, a)).isEqualTo(a);
        assertThat(ScopePredicate.not(ScopePredicate.not(a))).isEqualTo(a);
        assertThat(ScopePredicate.not(ScopePredicate.all())).isEqualTo(ScopePredicate.none());
        assertThat(ScopePredicate.not(ScopePredicate.none())).isEqualTo(ScopePredicate.all());
    }

    @Test
    void andOrEvaluateParts() {
        var row = row(Map.of("employee_no", "E-1", "org_unit_id", "D01"));

        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.and(
                                        ScopePredicate.eq("employee_no", "E-1"),
                                        ScopePredicate.eq("org_unit_id", "D01")),
                                schema()))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.or(
                                        ScopePredicate.eq("employee_no", "E-9"),
                                        ScopePredicate.eq("org_unit_id", "D01")),
                                schema()))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.or(
                                        ScopePredicate.eq("employee_no", "E-9"),
                                        ScopePredicate.eq("org_unit_id", "D02")),
                                schema()))
                .isFalse();
    }
}
