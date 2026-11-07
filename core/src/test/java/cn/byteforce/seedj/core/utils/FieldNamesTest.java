package cn.byteforce.seedj.core.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 字段名转换：命令 record 与 schema 共用同一份 snake_case 规则。 */
class FieldNamesTest {

    @Test
    @DisplayName("camelCase 转为 snake_case")
    void camelCaseToSnakeCase() {
        assertThat(FieldNames.snakeCase("employeeNo")).isEqualTo("employee_no");
        assertThat(FieldNames.snakeCase("orgUnitId")).isEqualTo("org_unit_id");
        assertThat(FieldNames.snakeCase("URL")).isEqualTo("u_r_l");
    }

    @Test
    @DisplayName("已经是 snake_case 的名称保持不变")
    void snakeCaseIsStable() {
        assertThat(FieldNames.snakeCase("employee_no")).isEqualTo("employee_no");
        assertThat(FieldNames.snakeCase("name")).isEqualTo("name");
    }
}
