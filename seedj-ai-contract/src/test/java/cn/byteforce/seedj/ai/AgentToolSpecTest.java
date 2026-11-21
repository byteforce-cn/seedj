package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具登记项的构造口径：命令类型必填且不得空白，动作分类必填。
 *
 * <p>动作分类由登记方声明而不是调用方传入；缺分类的登记项会让守卫无从判断审批强度。
 */
class AgentToolSpecTest {

    @Test
    @DisplayName("命令类型缺失时拒绝")
    void command_type_is_mandatory() {
        assertThatThrownBy(() -> new AgentToolSpec(null, ActionClass.READ_ONLY))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("commandType");
    }

    @Test
    @DisplayName("命令类型为空白时拒绝")
    void blank_command_type_is_rejected() {
        assertThatThrownBy(() -> new AgentToolSpec(" ", ActionClass.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandType");
    }

    @Test
    @DisplayName("命令类型与动作分类原样保留")
    void carries_command_type_and_action_class() {
        AgentToolSpec spec = new AgentToolSpec("ticket.create", ActionClass.REVERSIBLE);

        assertThat(spec.commandType()).isEqualTo("ticket.create");
        assertThat(spec.actionClass()).isEqualTo(ActionClass.REVERSIBLE);
    }

    @Test
    @DisplayName("动作分类缺失时拒绝")
    void action_class_is_mandatory() {
        assertThatThrownBy(() -> new AgentToolSpec("ticket.create", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionClass");
    }
}
