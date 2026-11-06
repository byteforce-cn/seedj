package cn.byteforce.seedj.server.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.core.MigrationChecksums;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1 冻结门禁：签入的 literal SQL 是发布契约，任何改动都会让已有库 checksum 冲突。
 *
 * <p>加字段请新增 V2；改 V1 只能通过本测试的显式失败来推动一次迁移方案评审。</p>
 */
class HrEmployeeMigrationTest {

    /** 2026-11-06 冻结的 V1 checksum（{@link MigrationChecksums#normalize} 之后）。 */
    private static final String V1_CHECKSUM =
            "0ff7fb138d068b21d6e2fbbe683b65419e8764f5472027923fdd41f43049463d";

    @Test
    @DisplayName("V1 是签入的 literal SQL，checksum 已冻结")
    void v1IsFrozen() {
        String sql = HrEmployeeSchemaCapability.v1();

        assertThat(sql).doesNotStartWith("-- Generated candidate");
        assertThat(MigrationChecksums.checksum(sql)).isEqualTo(V1_CHECKSUM);
    }

    @Test
    @DisplayName("V1 覆盖双时间轴元字段、业务列、排他约束与 as-of 函数")
    void v1ContainsTemporalContract() {
        String sql = HrEmployeeSchemaCapability.v1();

        assertThat(sql).contains("CREATE TABLE hr_employee (");
        assertThat(sql).contains("pk_hr_employee PRIMARY KEY (id, recorded_at)");
        assertThat(sql).contains("ex_hr_employee_valid_overlap EXCLUDE USING gist");
        assertThat(sql).contains("uq_hr_employee_current");
        assertThat(sql).contains("CREATE FUNCTION hr_employee_as_of_valid");
        assertThat(sql).contains("CREATE FUNCTION hr_employee_as_of_recorded");
        assertThat(sql).contains("CREATE FUNCTION hr_employee_as_of_bitemporal");
        for (String column : java.util.List.of(
                "employee_no TEXT",
                "name TEXT",
                "status TEXT",
                "hire_date DATE",
                "employment_type TEXT",
                "work_location TEXT",
                "phone TEXT",
                "id_number_hash TEXT",
                "org_unit_id TEXT")) {
            assertThat(sql).as("column %s is in V1", column).contains(column);
        }
    }
}
