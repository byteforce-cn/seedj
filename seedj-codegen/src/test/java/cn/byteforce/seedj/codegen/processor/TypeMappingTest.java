package cn.byteforce.seedj.codegen.processor;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.seedj.domain.FieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Java 类型到 FieldType 的映射。 */
class TypeMappingTest {

    @Test
    @DisplayName("支持的 Java 类型映射到稳定字段类型")
    void supportedTypes() {
        assertThat(TypeMapping.infer("java.lang.String", false)).isEqualTo(FieldType.TEXT);
        assertThat(TypeMapping.infer("int", false)).isEqualTo(FieldType.INTEGER);
        assertThat(TypeMapping.infer("java.lang.Long", false)).isEqualTo(FieldType.LONG);
        assertThat(TypeMapping.infer("java.math.BigDecimal", false)).isEqualTo(FieldType.DECIMAL);
        assertThat(TypeMapping.infer("boolean", false)).isEqualTo(FieldType.BOOLEAN);
        assertThat(TypeMapping.infer("java.time.LocalDate", false)).isEqualTo(FieldType.DATE);
        assertThat(TypeMapping.infer("java.time.Instant", false)).isEqualTo(FieldType.TIMESTAMP);
        assertThat(TypeMapping.infer("java.util.UUID", false)).isEqualTo(FieldType.UUID);
    }

    @Test
    @DisplayName("枚举按 TEXT 处理")
    void enumsAreText() {
        assertThat(TypeMapping.infer("com.example.Status", true)).isEqualTo(FieldType.TEXT);
    }

    @Test
    @DisplayName("无法映射的类型返回 null，由处理器报编译错误")
    void unsupportedTypes() {
        assertThat(TypeMapping.infer("java.util.List", false)).isNull();
        assertThat(TypeMapping.infer("java.util.Map", false)).isNull();
    }
}
