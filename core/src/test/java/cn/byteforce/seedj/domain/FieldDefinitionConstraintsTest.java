package cn.byteforce.seedj.domain;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 字段值域约束：enum/pattern/length/precision 在 schema.validate 中失败关闭。 */
class FieldDefinitionConstraintsTest {

    @Test
    @DisplayName("enum 未声明的值被拒绝")
    void rejectsValueOutsideDeclaredEnum() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("status", FieldType.TEXT, true, false, true,
                        true, false, false, Privacy.PUBLIC, null,
                        new FieldDefinition.Constraints(
                                Set.of("active", "terminated"), null, null, null, false,
                                null, null)))
                .build();

        assertThatThrownBy(() -> schema.validate(Map.of("status", "pending")))
                .isInstanceOf(ContractException.class)
                .satisfies(error -> assertThat(((ContractException) error).code())
                        .isEqualTo(ContractErrorCode.INVALID_PAYLOAD))
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("pattern 不匹配的值被拒绝")
    void rejectsValueNotMatchingPattern() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("employee_no", FieldType.TEXT, true, false, true,
                        false, false, false, Privacy.PUBLIC, null,
                        new FieldDefinition.Constraints(Set.of(), "^EMP-[0-9]{6,}$", null,
                                null, false, null, null)))
                .build();

        assertThat(schema.validate(Map.of("employee_no", "EMP-000001")))
                .containsEntry("employee_no", "EMP-000001");
        assertThatThrownBy(() -> schema.validate(Map.of("employee_no", "EMP-abc")))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("employee_no");
    }

    @Test
    @DisplayName("minLength/maxLength 生效")
    void enforcesLength() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("name", FieldType.TEXT, true, false, true,
                        false, false, false, Privacy.PUBLIC, null,
                        new FieldDefinition.Constraints(Set.of(), null, 1, 5, false, null,
                                null)))
                .build();

        assertThatThrownBy(() -> schema.validate(Map.of("name", "")))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> schema.validate(Map.of("name", "abcdef")))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("decimal scale 超限被拒绝")
    void rejectsDecimalWithTooManyFractionDigits() {
        ObjectSchema schema = ObjectSchema.builder("invoice", "fin_invoice")
                .field(new FieldDefinition("amount", FieldType.DECIMAL, true, false, true,
                        false, false, false, Privacy.PUBLIC, null,
                        new FieldDefinition.Constraints(Set.of(), null, null, null, false,
                                18, 2)))
                .build();

        assertThat(schema.validate(Map.of("amount", new BigDecimal("12.34"))))
                .containsEntry("amount", new BigDecimal("12.34"));
        assertThatThrownBy(() -> schema.validate(Map.of("amount", new BigDecimal("1.234"))))
                .isInstanceOf(ContractException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("null 跳过值域校验，必填由 required 分支处理")
    void nullSkipsValueConstraints() {
        ObjectSchema schema = ObjectSchema.builder("employee", "hr_employee")
                .field(new FieldDefinition("status", FieldType.TEXT, false, false, true,
                        false, false, false, Privacy.PUBLIC, null,
                        new FieldDefinition.Constraints(Set.of("active"), null, null, null,
                                false, null, null)))
                .build();

        assertThat(schema.validate(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("约束与字段类型不匹配时在声明期失败")
    void rejectsConstraintsThatDoNotMatchType() {
        assertThatThrownBy(() -> new FieldDefinition("amount", FieldType.INTEGER, false, false,
                true, false, false, false, Privacy.PUBLIC, null,
                new FieldDefinition.Constraints(Set.of(), null, null, null, false, 18, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
        assertThatThrownBy(() -> FieldDefinition.of("status", FieldType.TEXT)
                .withConstraints(new FieldDefinition.Constraints(Set.of("a"), null, null, null,
                        false, 10, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precision");
    }

    @Test
    @DisplayName("约束构造期校验边界")
    void constraintsValidateRanges() {
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, -1, null,
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minLength");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, null, -1,
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxLength");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, 2, 1,
                false, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minLength");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, null, null,
                false, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("precision");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, null, null,
                false, 4, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scale");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), null, null, null,
                false, 2, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scale");
        assertThatThrownBy(() -> new FieldDefinition.Constraints(Set.of(), "(?", null, null,
                false, null, null))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class);
        assertThat(FieldDefinition.Constraints.NONE.enumValues()).isEmpty();
        assertThat(FieldDefinition.Constraints.NONE.pattern()).isNull();
    }

    @Test
    @DisplayName("enum/pattern/length 只能用于 TEXT")
    void textOnlyConstraintsAreEnforced() {
        assertThatThrownBy(() -> new FieldDefinition("n", FieldType.INTEGER, false, false,
                true, false, false, false, Privacy.PUBLIC, null,
                new FieldDefinition.Constraints(Set.of("a"), null, null, null, false, null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enum");
        assertThatThrownBy(() -> new FieldDefinition("n", FieldType.INTEGER, false, false,
                true, false, false, false, Privacy.PUBLIC, null,
                new FieldDefinition.Constraints(Set.of(), "^x$", null, null, false, null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pattern");
        assertThatThrownBy(() -> new FieldDefinition("n", FieldType.INTEGER, false, false,
                true, false, false, false, Privacy.PUBLIC, null,
                new FieldDefinition.Constraints(Set.of(), null, 1, null, false, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("length");
    }

    @Test
    @DisplayName("withers 保留约束")
    void withersKeepConstraints() {
        FieldDefinition.Constraints constraints = new FieldDefinition.Constraints(
                Set.of("active"), null, null, null, true, null, null);

        assertThat(FieldDefinition.of("status", FieldType.TEXT)
                .withConstraints(constraints).withRequired().constraints())
                .isEqualTo(constraints);
    }
}
