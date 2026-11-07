package cn.byteforce.seedj.domain.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 聚合测试期断言：命令后的状态必须与事件重放的状态一致。 */
class AggregateAssertionsTest {

    @Test
    @DisplayName("状态一致时静默通过")
    void passesWhenStatesMatch() {
        assertThatCode(
                        () ->
                                AggregateAssertions.assertReplayable(
                                        "frozen", "frozen", value -> value))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("状态不一致时给出修复指引")
    void failsWhenStatesDiffer() {
        assertThatThrownBy(
                        () ->
                                AggregateAssertions.assertReplayable(
                                        "frozen", "unfrozen", value -> value))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("live    : frozen")
                .hasMessageContaining("replayed: unfrozen")
                .hasMessageContaining("without raising an event");
    }

    @Test
    @DisplayName("参数不可为空")
    void requiresArguments() {
        assertThatThrownBy(() -> AggregateAssertions.assertReplayable(null, "x", value -> value))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("live aggregate");
        assertThatThrownBy(() -> AggregateAssertions.assertReplayable("x", null, value -> value))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rehydrated aggregate");
        assertThatThrownBy(() -> AggregateAssertions.assertReplayable("x", "x", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("stateOf");
    }
}
