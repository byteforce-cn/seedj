package cn.byteforce.seedj.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 谓词合成与内存求值：扁平化、吸收元与嵌套短路。 */
class ScopePredicatesTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");

    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");

    private static final ScopePredicate A = ScopePredicate.eq("org_unit_id", "D01");

    private static final ScopePredicate B = ScopePredicate.eq("org_unit_id", "D02");

    private static final ScopePredicate C = ScopePredicate.eq("org_unit_id", "D03");

    @Test
    @DisplayName("嵌套 And 被扁平化并去重")
    void nestedAndIsFlattened() {
        ScopePredicate nested = ScopePredicates.and(List.of(A, ScopePredicates.and(List.of(B, A))));

        assertThat(nested).isEqualTo(new ScopePredicate.And(List.of(A, B)));
    }

    @Test
    @DisplayName("嵌套 And 内的 None 立即短路为 None")
    void nestedAndShortCircuitsOnNone() {
        assertThat(
                        ScopePredicates.and(
                                List.of(A, ScopePredicates.and(List.of(B, ScopePredicate.none())))))
                .isEqualTo(ScopePredicate.none());
    }

    @Test
    @DisplayName("嵌套 Or 内的 All 立即短路为 All")
    void nestedOrShortCircuitsOnAll() {
        assertThat(
                        ScopePredicates.or(
                                List.of(A, ScopePredicates.or(List.of(B, ScopePredicate.all())))))
                .isEqualTo(ScopePredicate.all());
    }

    @Test
    @DisplayName("嵌套 Or 被扁平化并去重")
    void nestedOrIsFlattened() {
        ScopePredicate nested = ScopePredicates.or(List.of(A, ScopePredicates.or(List.of(B, A))));

        assertThat(nested).isEqualTo(new ScopePredicate.Or(List.of(A, B)));
    }

    @Test
    @DisplayName("单元素合成化简为原谓词，空合成回到单位元/吸收元")
    void combineSimplifies() {
        assertThat(ScopePredicates.and(List.of(A))).isEqualTo(A);
        assertThat(ScopePredicates.or(List.of(A))).isEqualTo(A);
        assertThat(ScopePredicates.and(List.of())).isEqualTo(ScopePredicate.all());
        assertThat(ScopePredicates.or(List.of())).isEqualTo(ScopePredicate.none());
    }

    @Test
    @DisplayName("in 谓词在内存求值时按声明类型转换常量")
    void matchesCoercesInValues() {
        ObjectSchema schema =
                ObjectSchema.builder("employee", "hr_employee")
                        .field("employee_no", FieldType.TEXT)
                        .field("org_unit_id", FieldType.TEXT)
                        .build();
        Map<String, Object> fields = Map.of("employee_no", "E-1", "org_unit_id", "D01");
        TemporalRow row = new TemporalRow("employee", version(), fields);

        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.in("org_unit_id", List.of("D01", "D02")),
                                schema))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row, ScopePredicate.in("org_unit_id", List.of("D03")), schema))
                .isFalse();
        assertThat(ScopePredicates.matches(row, ScopePredicate.notNull("employee_no"), schema))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                row,
                                ScopePredicate.not(
                                        ScopePredicate.in("org_unit_id", List.of("D03"))),
                                schema))
                .isTrue();
        assertThat(ScopePredicates.matches(row, ScopePredicate.and(A, C), schema)).isFalse();
        assertThat(ScopePredicates.matches(row, ScopePredicate.or(A, C), schema)).isTrue();
    }

    @Test
    @DisplayName("Map 版本与 TemporalRow 版本语义一致")
    void matchesMapVersion() {
        ObjectSchema schema =
                ObjectSchema.builder("employee", "hr_employee")
                        .field("org_unit_id", FieldType.TEXT)
                        .field("name", FieldType.TEXT)
                        .build();
        Map<String, Object> fields = Map.of("org_unit_id", "D01", "name", "张三");

        assertThat(ScopePredicates.matches(fields, ScopePredicate.eq("org_unit_id", "D01"), schema))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                fields,
                                ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D02")),
                                schema))
                .isTrue();
        assertThat(
                        ScopePredicates.matches(
                                Map.of("name", "张三"),
                                ScopePredicate.notNull("org_unit_id"),
                                schema))
                .isFalse();
    }

    private static ObjectVersion version() {
        return new ObjectVersion(ID, 1, NOW, null, NOW, null, ID, NOW, null, null);
    }
}
