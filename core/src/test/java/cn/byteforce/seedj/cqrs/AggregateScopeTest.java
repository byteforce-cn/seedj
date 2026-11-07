package cn.byteforce.seedj.cqrs;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.domain.FieldType;
import cn.byteforce.seedj.domain.ObjectSchema;
import cn.byteforce.seedj.domain.ScopePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AggregateScopeTest {

    private static final ObjectSchema SCHEMA = ObjectSchema.builder("employee", "hr_employee")
            .field("employee_no", FieldType.TEXT)
            .field("department", FieldType.TEXT)
            .field("active", FieldType.BOOLEAN)
            .build();

    @Test
    @DisplayName("snake_case 字段通过访问器读取")
    void matchesSnakeCaseFieldsThroughAccessors() {
        AggregateScope.requireWithin(new Employee("E-1", "D01", true),
                ScopePredicate.eq("employee_no", "E-1"), SCHEMA);
    }

    @Test
    @DisplayName("getter / is 命名也支持")
    void getterAndBooleanNamingAreSupported() {
        AggregateScope.requireWithin(new Employee("E-1", "D01", true),
                ScopePredicate.and(ScopePredicate.eq("department", "D01"),
                        ScopePredicate.eq("active", true)), SCHEMA);
    }

    @Test
    @DisplayName("越界抛 FORBIDDEN")
    void deniesOutsideScope() {
        assertThatThrownBy(() -> AggregateScope.requireWithin(new Employee("E-1", "D02", true),
                ScopePredicate.eq("department", "D01"), SCHEMA))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    @Test
    @DisplayName("缺少访问器失败关闭并报字段名")
    void missingAccessorFailsClosed() {
        assertThatThrownBy(() -> AggregateScope.requireWithin(new Object(),
                ScopePredicate.eq("department", "D01"), SCHEMA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("department");
    }

    @Test
    @DisplayName("All 不触发任何反射")
    void allScopeUsesNoAccessors() {
        AggregateScope.requireWithin(new Object(), ScopePredicate.all(), SCHEMA);
    }

    @Test
    @DisplayName("None 直接拒绝且不需要访问器")
    void noneScopeDeniesWithoutAccessors() {
        assertThatThrownBy(() -> AggregateScope.requireWithin(new Object(),
                ScopePredicate.none(), SCHEMA))
                .isInstanceOfSatisfying(ContractException.class, error ->
                        assertThat(error.code()).isEqualTo(ContractErrorCode.FORBIDDEN));
    }

    static final class Employee {

        private final String employeeNo;

        private final String department;

        private final boolean active;

        Employee(String employeeNo, String department, boolean active) {
            this.employeeNo = employeeNo;
            this.department = department;
            this.active = active;
        }

        public String employeeNo() {
            return employeeNo;
        }

        public String getDepartment() {
            return department;
        }

        public boolean isActive() {
            return active;
        }
    }
}
