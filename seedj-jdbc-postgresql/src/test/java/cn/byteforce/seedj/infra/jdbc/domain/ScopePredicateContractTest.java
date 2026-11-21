package cn.byteforce.seedj.infra.jdbc.domain;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 谓词契约：同一组用例在内存求值与 PostgreSQL 编译器上必须得到相同的行集合。
 *
 * <p>重点锁定 {@code Not} 对 {@code NULL} 的二值语义：字段为 {@code NULL} 时 {@code
 * Not(Eq(...))} 为真。两套实现分叉过一次（{@code NOT (p)} vs {@code IS NOT TRUE}）， 这组用例防止回潮。
 */
@Testcontainers
class ScopePredicateContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("seedj")
                    .withUsername("seedj")
                    .withPassword("seedj");

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("scope", "scope_row")
                    .field(FieldDefinition.of("org_unit_id", FieldType.TEXT))
                    .field(FieldDefinition.of("status", FieldType.TEXT))
                    .build();

    private static final Set<String> PROJECTED = Set.of("org_unit_id", "status");

    private static final List<Row> ROWS =
            List.of(
                    new Row(1, "D01", "ACTIVE"),
                    new Row(2, null, "ACTIVE"),
                    new Row(3, "D02", null),
                    new Row(4, "D01", null));

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void seed() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(
                "CREATE TABLE scope_row (id INT PRIMARY KEY, org_unit_id TEXT, status TEXT)");
        for (Row row : ROWS) {
            jdbc.update(
                    "INSERT INTO scope_row (id, org_unit_id, status) VALUES (?, ?, ?)",
                    row.id(),
                    row.orgUnitId(),
                    row.status());
        }
    }

    static Stream<ScopePredicate> predicates() {
        return Stream.of(
                ScopePredicate.all(),
                ScopePredicate.none(),
                ScopePredicate.eq("org_unit_id", "D01"),
                ScopePredicate.eq("org_unit_id", null),
                ScopePredicate.in("org_unit_id", List.of("D01", "D02")),
                new ScopePredicate.In("org_unit_id", List.of()),
                ScopePredicate.notNull("org_unit_id"),
                ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D01")),
                ScopePredicate.not(ScopePredicate.eq("org_unit_id", null)),
                ScopePredicate.not(ScopePredicate.notNull("status")),
                ScopePredicates.and(
                        List.of(
                                ScopePredicate.not(ScopePredicate.eq("org_unit_id", "D01")),
                                ScopePredicate.notNull("status"))),
                ScopePredicates.or(
                        List.of(
                                ScopePredicate.notNull("org_unit_id"),
                                ScopePredicate.eq("status", "ACTIVE"))));
    }

    @ParameterizedTest
    @MethodSource("predicates")
    @DisplayName("内存求值与 SQL 编译命中同一组行")
    void memoryAndSqlAgree(ScopePredicate predicate) {
        assertThat(sqlMatches(predicate))
                .as("SQL 命中行与内存求值不一致：%s", predicate)
                .isEqualTo(memoryMatches(predicate));
    }

    private static Set<Integer> sqlMatches(ScopePredicate predicate) {
        ScopeSql.Clause clause = ScopeSql.compile(predicate, SCHEMA, PROJECTED);
        List<Integer> ids =
                jdbc.queryForList(
                        "SELECT id FROM (SELECT id, org_unit_id, status FROM scope_row) seedj_q"
                                + " WHERE "
                                + clause.sql(),
                        Integer.class,
                        clause.args().toArray());
        return new LinkedHashSet<>(ids);
    }

    private static Set<Integer> memoryMatches(ScopePredicate predicate) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Row row : ROWS) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("org_unit_id", row.orgUnitId());
            fields.put("status", row.status());
            if (ScopePredicates.matches(fields, predicate, SCHEMA)) {
                ids.add(row.id());
            }
        }
        return ids;
    }

    /** 一行测试数据；{@code null} 表示 SQL NULL。 */
    private record Row(Integer id, String orgUnitId, String status) {}
}
