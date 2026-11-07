package cn.byteforce.seedj.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 自定义查询声明：只允许单条 SELECT + 标识符白名单，构造期失败关闭。 */
class QuerySpecTest {

    @Test
    @DisplayName("合法声明规整 SQL（去首尾空白）并冻结集合视图")
    void acceptsReviewedSelect() {
        QuerySpec spec =
                new QuerySpec(
                        "employee",
                        "  SELECT employee_no FROM hr_employee ",
                        Set.of("employee_no"),
                        null,
                        List.of("employee_no"),
                        "employee_no");

        assertThat(spec.sql()).isEqualTo("SELECT employee_no FROM hr_employee");
        assertThat(spec.parameters()).isEmpty();
        assertThatThrownBy(() -> spec.fields().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("objectType 不可为空")
    void rejectsBlankObjectType() {
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        " ",
                                        "SELECT a FROM t",
                                        Set.of("a"),
                                        Set.of(),
                                        List.of("a"),
                                        "a"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains("object type is required");
                        });
    }

    @Test
    @DisplayName("只允许单条 SELECT，且不含语句分隔符与注释")
    void rejectsUnsafeSql() {
        assertInvalid("UPDATE t SET a = 1", Set.of("a"), "single SELECT");
        assertInvalid("SELECT a FROM t; DROP TABLE t", Set.of("a"), "statement separators");
        assertInvalid("SELECT a FROM t -- comment", Set.of("a"), "statement separators");
        assertInvalid("SELECT a /* comment */ FROM t", Set.of("a"), "statement separators");
    }

    @Test
    @DisplayName("字段白名单不可为空，字段名必须是标识符")
    void rejectsIllegalFields() {
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        Set.of(),
                                        Set.of(),
                                        List.of("a"),
                                        "a"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("at least one field"));
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT A FROM t",
                                        Set.of("A"),
                                        Set.of(),
                                        List.of("A"),
                                        "A"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("illegal field: A"));
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        null,
                                        Set.of(),
                                        List.of("a"),
                                        "a"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("参数名必须是标识符")
    void rejectsIllegalParameterName() {
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t WHERE a = :Bad",
                                        Set.of("a"),
                                        Set.of("Bad"),
                                        List.of("a"),
                                        "a"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("illegal parameter: Bad"));
    }

    @Test
    @DisplayName("排序字段必须是字段白名单的子集且非空，默认排序必须在其中")
    void rejectsIllegalSortDeclaration() {
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        Set.of("a"),
                                        Set.of(),
                                        List.of("b"),
                                        "b"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("field whitelist: b"));
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        Set.of("a"),
                                        Set.of(),
                                        List.of(),
                                        "a"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.reason()).contains("at least one sortable field"));
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        Set.of("a"),
                                        Set.of(),
                                        null,
                                        "a"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error ->
                                assertThat(error.reason()).contains("at least one sortable field"));
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        "SELECT a FROM t",
                                        Set.of("a"),
                                        Set.of(),
                                        List.of("a"),
                                        "b"))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> assertThat(error.reason()).contains("default sort"));
    }

    @Test
    @DisplayName("报表查询禁止 UNION；JOIN 仍由 reviewed SQL + scope 投影约束")
    void rejectsUnion() {
        assertInvalid("SELECT a FROM t UNION SELECT a FROM u", Set.of("a"), "union");
    }

    private static void assertInvalid(String sql, Set<String> fields, String hint) {
        assertThatThrownBy(
                        () ->
                                new QuerySpec(
                                        "employee",
                                        sql,
                                        fields,
                                        Set.of(),
                                        List.of(fields.iterator().next()),
                                        fields.iterator().next()))
                .isInstanceOfSatisfying(
                        ContractException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo(ContractErrorCode.INVALID_PAYLOAD);
                            assertThat(error.reason()).contains(hint);
                        });
    }
}
