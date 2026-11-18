package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 策略引用的构造口径：引擎名必填且不得空白，规则列表缺省为空。
 *
 * <p>{@link PolicyRef#none()} 是 T0 唯一的合法取值：它显式声明“未评估”，让后续接入策略时能把 “没评估”与“评估通过”区分开。
 */
class PolicyRefTest {

    @Test
    @DisplayName("none() 声明未评估：不绑定策略包、无规则、无解释")
    void none_declares_not_evaluated() {
        PolicyRef policy = PolicyRef.none();

        assertThat(policy.engine()).isEqualTo("none");
        assertThat(policy.verdict()).isEqualTo("not_evaluated");
        assertThat(policy.bundle()).isNull();
        assertThat(policy.bundleVersion()).isNull();
        assertThat(policy.epoch()).isNull();
        assertThat(policy.ruleIds()).isEmpty();
        assertThat(policy.explainRef()).isNull();
    }

    @Test
    @DisplayName("规则列表缺省为空且不可变")
    void rule_ids_default_to_empty_list() {
        PolicyRef policy = new PolicyRef("opa", "bundle-a", 1L, 2L, "deny", null, null);

        assertThat(policy.ruleIds()).isEmpty();
        assertThatThrownBy(() -> policy.ruleIds().add("r-1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("规则列表被复制为不可变")
    void rule_ids_are_copied() {
        PolicyRef policy = new PolicyRef("opa", null, null, null, "deny", List.of("r-1"), null);

        assertThat(policy.ruleIds()).containsExactly("r-1");
    }

    @Test
    @DisplayName("引擎名缺失时拒绝")
    void engine_is_mandatory() {
        assertThatThrownBy(() -> new PolicyRef(null, null, null, null, "allow", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("engine");
    }

    @Test
    @DisplayName("引擎名为空白时拒绝")
    void blank_engine_is_rejected() {
        assertThatThrownBy(() -> new PolicyRef(" ", null, null, null, "allow", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("engine");
    }
}
