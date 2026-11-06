package cn.byteforce.seedj.codegen.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 命名转换与 {@code ObjectModules} 的载荷字段推导保持一致。 */
class NamesTest {

    @Test
    @DisplayName("camelCase 转 snake_case 与命令载荷推导一致")
    void snakeCase() {
        assertThat(Names.snakeCase("employeeNo")).isEqualTo("employee_no");
        assertThat(Names.snakeCase("orgUnitId")).isEqualTo("org_unit_id");
        assertThat(Names.snakeCase("idNumberHash")).isEqualTo("id_number_hash");
        assertThat(Names.snakeCase("name")).isEqualTo("name");
        assertThat(Names.snakeCase("phone2")).isEqualTo("phone2");
    }
}
