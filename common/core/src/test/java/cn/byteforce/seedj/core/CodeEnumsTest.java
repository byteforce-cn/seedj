package cn.byteforce.seedj.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CodeEnums} 契约测试：按 code 查找、必填校验与不可变映射。
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CodeEnumsTest {

    @Test
    void ofReturnsEnumByCode() {
        assertThat(CodeEnums.of(SampleCode.class, 10)).isEqualTo(SampleCode.OK);
        assertThat(CodeEnums.of(SampleCode.class, 20)).isEqualTo(SampleCode.ERROR);
    }

    @Test
    void ofReturnsNullForUnknownCodeOrNull() {
        assertThat(CodeEnums.of(SampleCode.class, 99)).isNull();
        assertThat(CodeEnums.of(SampleCode.class, null)).isNull();
    }

    @Test
    void ofRequiredReturnsEnumByCode() {
        assertThat(CodeEnums.ofRequired(SampleCode.class, 20)).isEqualTo(SampleCode.ERROR);
    }

    @Test
    void ofRequiredThrowsForUnknownCode() {
        assertThatThrownBy(() -> CodeEnums.ofRequired(SampleCode.class, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99")
                .hasMessageContaining("SampleCode");
    }

    @Test
    void toMapBuildsUnmodifiableCodeIndex() {
        Map<Integer, SampleCode> map = CodeEnums.toMap(SampleCode.class);

        assertThat(map).containsEntry(10, SampleCode.OK).containsEntry(20, SampleCode.ERROR);
        assertThatThrownBy(() -> map.put(30, SampleCode.OK))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
