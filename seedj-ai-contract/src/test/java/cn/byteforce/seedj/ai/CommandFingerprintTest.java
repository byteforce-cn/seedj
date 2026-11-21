package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指纹输入的构造口径：命令类型必填且不得空白，载荷必填，目标列表缺省为空。
 *
 * <p>指纹输入是审批与幂等的共同前像；缺字段时算出来的摘要会把不同命令压成同一个指纹， 比对就失去意义，因此必须在构造期拒绝。
 */
class CommandFingerprintTest {

    private static final UUID TARGET = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("命令类型缺失按空白处理并拒绝")
    void missing_type_is_rejected() {
        assertThatThrownBy(() -> new CommandFingerprint(null, Map.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("命令类型为空白时拒绝")
    void blank_type_is_rejected() {
        assertThatThrownBy(() -> new CommandFingerprint("  ", Map.of(), List.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    @DisplayName("载荷缺失时拒绝")
    void payload_is_mandatory() {
        assertThatThrownBy(() -> new CommandFingerprint("ticket.create", null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("targets 缺省为空列表")
    void targets_default_to_empty_list() {
        CommandFingerprint fingerprint =
                new CommandFingerprint("ticket.create", Map.of(), null, 7L);

        assertThat(fingerprint.targets()).isEmpty();
        assertThat(fingerprint.expectedVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("targets 被复制：外部列表后续修改不影响指纹输入")
    void targets_are_copied() {
        List<UUID> targets = new ArrayList<>(List.of(TARGET));
        CommandFingerprint fingerprint =
                new CommandFingerprint("ticket.create", Map.of(), targets, 7L);

        targets.add(UUID.randomUUID());

        assertThat(fingerprint.targets()).containsExactly(TARGET);
    }
}
