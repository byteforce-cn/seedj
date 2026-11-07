package cn.byteforce.seedj.infra.local.iam;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.iam.ScimComparisonOperator;
import cn.byteforce.seedj.iam.ScimFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 内存 SCIM 过滤求值：运算符、集合属性、嵌套路径与空值语义。 */
class MemoryScimFiltersTest {

    private final Map<String, Object> attributes = new LinkedHashMap<>();

    MemoryScimFiltersTest() {
        attributes.put("userName", "Alice");
        attributes.put("active", true);
        attributes.put("age", 30);
        attributes.put("emails.value", List.of("alice@example.com", "a@corp.example"));
        attributes.put("meta", Map.of("department", "Engineering"));
        attributes.put("empty", List.of());
    }

    @Test
    void nullFilterMatchesEverything() {
        assertThat(MemoryScimFilters.matches(null, attributes)).isTrue();
    }

    @Test
    void comparesCaseInsensitivelyForStringOperators() {
        assertThat(matches("userName", ScimComparisonOperator.EQ, "Alice")).isTrue();
        assertThat(matches("userName", ScimComparisonOperator.EQ, "alice")).isFalse();
        assertThat(matches("userName", ScimComparisonOperator.CO, "LIC")).isTrue();
        assertThat(matches("userName", ScimComparisonOperator.SW, "al")).isTrue();
        assertThat(matches("userName", ScimComparisonOperator.EW, "CE")).isTrue();
    }

    @Test
    void comparesNumbersAndDates() {
        assertThat(matches("age", ScimComparisonOperator.GT, 29)).isTrue();
        assertThat(matches("age", ScimComparisonOperator.LT, 30)).isFalse();
        assertThat(matches("age", ScimComparisonOperator.GE, 30.0)).isTrue();
        assertThat(matches("age", ScimComparisonOperator.LE, 29)).isFalse();
    }

    @Test
    void equalityIsTypeAware() {
        assertThat(matches("active", ScimComparisonOperator.EQ, "true")).isTrue();
        assertThat(matches("age", ScimComparisonOperator.EQ, 30.0)).isTrue();
        assertThat(matches("missing", ScimComparisonOperator.EQ, null)).isTrue();
        assertThat(matches("missing", ScimComparisonOperator.NE, "x")).isTrue();
    }

    @Test
    void multiValuedAttributeMatchesAnyElement() {
        assertThat(matches("emails.value", ScimComparisonOperator.EQ, "a@corp.example")).isTrue();
        assertThat(matches("emails.value", ScimComparisonOperator.EW, "example.com")).isTrue();
        assertThat(matches("emails.value", ScimComparisonOperator.EQ, "bob@example.com")).isFalse();
    }

    @Test
    void presentRequiresNonNullAndNonEmptyCollection() {
        assertThat(MemoryScimFilters.matches(new ScimFilter.Present("userName"), attributes))
                .isTrue();
        assertThat(MemoryScimFilters.matches(new ScimFilter.Present("empty"), attributes))
                .isFalse();
        assertThat(MemoryScimFilters.matches(new ScimFilter.Present("missing"), attributes))
                .isFalse();
    }

    @Test
    void resolvesNestedPathsThroughMaps() {
        assertThat(matches("meta.department", ScimComparisonOperator.EQ, "Engineering")).isTrue();
        assertThat(matches("meta.missing", ScimComparisonOperator.EQ, "x")).isFalse();
        assertThat(matches("userName.length", ScimComparisonOperator.EQ, "x")).isFalse();
    }

    @Test
    void combinesAndOrNot() {
        ScimFilter engineer = new ScimFilter.Compare("userName", ScimComparisonOperator.SW, "al");
        ScimFilter active = new ScimFilter.Compare("active", ScimComparisonOperator.EQ, true);

        assertThat(
                        MemoryScimFilters.matches(
                                new ScimFilter.And(List.of(engineer, active)), attributes))
                .isTrue();
        assertThat(
                        MemoryScimFilters.matches(
                                new ScimFilter.And(List.of(engineer, new ScimFilter.Not(active))),
                                attributes))
                .isFalse();
        assertThat(
                        MemoryScimFilters.matches(
                                new ScimFilter.Or(
                                        List.of(
                                                new ScimFilter.Compare(
                                                        "userName",
                                                        ScimComparisonOperator.EQ,
                                                        "bob"),
                                                active)),
                                attributes))
                .isTrue();
        assertThat(MemoryScimFilters.matches(new ScimFilter.Not(active), attributes)).isFalse();
    }

    @Test
    void collectionAttributesSupportMembership() {
        Map<String, Object> group = Map.of("members.value", Set.of("u1", "u2"));

        assertThat(
                        MemoryScimFilters.matches(
                                new ScimFilter.Compare(
                                        "members.value", ScimComparisonOperator.EQ, "u2"),
                                group))
                .isTrue();
    }

    private boolean matches(String attribute, ScimComparisonOperator operator, Object value) {
        return MemoryScimFilters.matches(
                new ScimFilter.Compare(attribute, operator, value), attributes);
    }
}
