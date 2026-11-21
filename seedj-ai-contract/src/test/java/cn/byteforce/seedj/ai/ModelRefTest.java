package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 模型引用的构造口径：模型名与版本必填且不得空白，提示词版本与参数摘要可缺省。
 *
 * <p>名与版本是“同一模型为何给出不同判断”的定位依据；空白名会让审计无法还原决策条件。
 */
class ModelRefTest {

    @ParameterizedTest
    @ValueSource(strings = {"name", "version"})
    @DisplayName("模型名或版本缺失时按字段名拒绝")
    void missing_text_is_rejected(String component) {
        assertThatThrownBy(
                        () ->
                                new ModelRef(
                                        "name".equals(component) ? null : "seedj-agent",
                                        "version".equals(component) ? null : "2026-11",
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(component);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "version"})
    @DisplayName("模型名或版本为空白时按字段名拒绝")
    void blank_text_is_rejected(String component) {
        assertThatThrownBy(
                        () ->
                                new ModelRef(
                                        "name".equals(component) ? "" : "seedj-agent",
                                        "version".equals(component) ? "  " : "2026-11",
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(component);
    }

    @Test
    @DisplayName("提示词版本与参数摘要可缺省")
    void prompt_and_params_may_be_absent() {
        ModelRef model = new ModelRef("seedj-agent", "2026-11", null, null);

        assertThat(model.promptVer()).isNull();
        assertThat(model.paramsDigest()).isNull();
    }
}
