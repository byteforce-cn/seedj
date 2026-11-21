package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 命令引用的构造口径：{@code targets} 缺省为空列表并复制为不可变，命令类型必填且不得空白。
 *
 * <p>引用参与指纹比对；目标列表若共享调用方的可变列表，守卫就会在比对之后被改掉输入。
 */
class CommandRefTest {

    private static final UUID TARGET = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("targets 缺省为空列表，expectedVersion 保持缺省")
    void targets_default_to_empty_list() {
        CommandRef command = new CommandRef("ticket.create", null, null);

        assertThat(command.targets()).isEmpty();
        assertThat(command.expectedVersion()).isNull();
        assertThatThrownBy(() -> command.targets().add(TARGET))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("targets 被复制：外部列表后续修改不影响引用")
    void targets_are_copied() {
        List<UUID> targets = new ArrayList<>(List.of(TARGET));
        CommandRef command = new CommandRef("ticket.create", targets, 7L);

        targets.add(UUID.randomUUID());

        assertThat(command.targets()).containsExactly(TARGET);
        assertThat(command.expectedVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("命令类型缺失时拒绝")
    void type_is_mandatory() {
        assertThatThrownBy(() -> new CommandRef(null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("命令类型为空白时拒绝")
    void blank_type_is_rejected() {
        assertThatThrownBy(() -> new CommandRef(" ", List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }
}
