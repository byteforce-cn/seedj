package cn.byteforce.seedj.server.hr.employee;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 读视图由字段 record 生成：加字段不再手写第二份视图。 */
class HrEmployeeFieldsViewTest {

    @Test
    @DisplayName("view() 返回生成的视图类型")
    void viewIsGeneratedFromFieldsRecord() {
        assertThat(HrEmployeeFields.view()).isEqualTo(HrEmployeeFieldsView.class);
    }

    @Test
    @DisplayName("生成的视图包含元字段与可读字段，不包含 writeOnly 字段")
    void viewContainsMetaAndReadableFieldsOnly() {
        var names = Arrays.stream(HrEmployeeFieldsView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(names)
                .contains("id", "version", "validFrom", "validTo")
                .contains("employeeNo", "name", "status", "hireDate", "employmentType",
                        "workLocation", "contact", "idType", "orgUnitId", "terminatedAt",
                        "terminatedReason")
                .doesNotContain("idNumberHash");
    }
}
