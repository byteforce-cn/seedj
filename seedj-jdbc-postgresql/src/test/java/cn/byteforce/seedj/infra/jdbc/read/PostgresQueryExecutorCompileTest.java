package cn.byteforce.seedj.infra.jdbc.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.FieldDefinition;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.PageRequest;
import cn.byteforce.seedj.domain.ScopePredicate;
import cn.byteforce.seedj.domain.ScopePredicates;
import cn.byteforce.seedj.domain.SortDirection;
import cn.byteforce.seedj.read.QueryPlan;
import cn.byteforce.seedj.read.QuerySpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SQL 生成：命名参数绑定、行范围、排序与分页都由适配器编译。 */
class PostgresQueryExecutorCompileTest {

    private static final ObjectSchema SCHEMA =
            ObjectSchema.builder("employee", "hr_employee")
                    .field(FieldDefinition.required("employee_no", FieldType.TEXT).withSortable())
                    .field(FieldDefinition.of("org_unit_id", FieldType.TEXT).withFilterable())
                    .field(FieldDefinition.of("status", FieldType.TEXT).withFilterable())
                    .build();

    private static final QuerySpec SPEC =
            new QuerySpec(
                    "employee",
                    "SELECT employee_no, org_unit_id, status FROM hr_employee WHERE employee_no = :number",
                    Set.of("employee_no", "org_unit_id", "status"),
                    Set.of("number"),
                    List.of("employee_no"),
                    "employee_no");

    @Test
    @DisplayName("In 谓词编译为占位符列表并按类型转换常量")
    void compilesInPredicate() {
        PostgresQueryExecutor.CompiledQuery query =
                compile(ScopePredicate.in("org_unit_id", List.of("D01", "D02")), SPEC, values());

        assertThat(query.pageSql()).contains("seedj_q.\"org_unit_id\" IN (?, ?)");
        assertThat(query.pageArguments()).containsExactly("E-1", "D01", "D02", 20, 0L);
        assertThat(query.countSql())
                .isEqualTo(
                        "SELECT count(*) FROM ("
                                + inner()
                                + ") seedj_q"
                                + " WHERE seedj_q.\"org_unit_id\" IN (?, ?)");
        assertThat(query.countArguments()).containsExactly("E-1", "D01", "D02");
    }

    @Test
    @DisplayName("Not/Or/And/NotNull 谓词编译为嵌套条件，Not 使用 IS NOT TRUE")
    void compilesCompositePredicates() {
        ScopePredicate predicate =
                ScopePredicates.and(
                        List.of(
                                ScopePredicate.not(ScopePredicate.eq("status", "LEFT")),
                                ScopePredicate.or(
                                        ScopePredicate.notNull("org_unit_id"),
                                        ScopePredicate.in(
                                                "status", List.of("ACTIVE", "PENDING")))));

        PostgresQueryExecutor.CompiledQuery query = compile(predicate, SPEC, values());

        assertThat(query.pageSql())
                .contains(
                        "((seedj_q.\"status\" = ?) IS NOT TRUE"
                                + " AND (seedj_q.\"org_unit_id\" IS NOT NULL"
                                + " OR seedj_q.\"status\" IN (?, ?)))");
        assertThat(query.pageArguments())
                .containsExactly("E-1", "LEFT", "ACTIVE", "PENDING", 20, 0L);
    }

    @Test
    @DisplayName("空 In 列表与 null 等值退化为恒假，不绑定任何常量")
    void emptyInAndNullEqBecomeFalse() {
        PostgresQueryExecutor.CompiledQuery empty =
                compile(new ScopePredicate.In("org_unit_id", List.of()), SPEC, values());
        PostgresQueryExecutor.CompiledQuery nullEq =
                compile(new ScopePredicate.Eq("org_unit_id", null), SPEC, values());

        assertThat(empty.pageSql()).contains("WHERE FALSE");
        assertThat(empty.pageArguments()).containsExactly("E-1", 20, 0L);
        assertThat(nullEq.pageSql()).contains("WHERE FALSE");
        assertThat(nullEq.pageArguments()).containsExactly("E-1", 20, 0L);
    }

    @Test
    @DisplayName("排序与分页进入 SQL，方向来自查询计划")
    void compilesSortAndPage() {
        PostgresQueryExecutor.CompiledQuery query =
                compile(
                        ScopePredicate.all(),
                        SPEC,
                        values(),
                        "employee_no",
                        SortDirection.DESC,
                        new PageRequest(3, 5));

        assertThat(query.pageSql())
                .endsWith("ORDER BY seedj_q.\"employee_no\" DESC LIMIT ? OFFSET ?");
        assertThat(query.pageArguments()).containsExactly("E-1", 5, 10L);
    }

    @Test
    @DisplayName("行范围引用未投影字段时失败关闭")
    void scopeFieldMustBeProjected() {
        QuerySpec narrow =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no FROM hr_employee",
                        Set.of("employee_no"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");

        assertThatThrownBy(() -> compile(ScopePredicate.eq("org_unit_id", "D01"), narrow, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row scope uses field org_unit_id");
    }

    @Test
    @DisplayName("SQL 含位置占位符时拒绝执行")
    void rejectsPositionalPlaceholders() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no FROM hr_employee WHERE status = ?",
                        Set.of("employee_no"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");

        assertThatThrownBy(() -> compile(ScopePredicate.all(), spec, Map.of()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("named parameters");
                        });
    }

    @Test
    @DisplayName("SQL 引用未声明的命名参数时失败")
    void rejectsUndeclaredNamedParameter() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "SELECT employee_no, org_unit_id, status FROM hr_employee"
                                + " WHERE status = :state",
                        Set.of("employee_no", "org_unit_id", "status"),
                        Set.of(),
                        List.of("employee_no"),
                        "employee_no");

        assertThatThrownBy(() -> compile(ScopePredicate.all(), spec, Map.of()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.reason())
                                        .contains("undeclared parameter in query: state"));
    }

    private static String inner() {
        return "SELECT employee_no, org_unit_id, status FROM hr_employee"
                + " WHERE employee_no = ?";
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", "E-1");
        return values;
    }

    private static PostgresQueryExecutor.CompiledQuery compile(
            ScopePredicate scope, QuerySpec spec, Map<String, Object> values) {
        return compile(
                scope, spec, values, spec.defaultSort(), SortDirection.ASC, PageRequest.defaults());
    }

    private static PostgresQueryExecutor.CompiledQuery compile(
            ScopePredicate scope,
            QuerySpec spec,
            Map<String, Object> values,
            String sort,
            SortDirection direction,
            PageRequest page) {
        return PostgresQueryExecutor.compile(
                new QueryPlan(spec, values, sort, direction, page, scope, SCHEMA));
    }
}
