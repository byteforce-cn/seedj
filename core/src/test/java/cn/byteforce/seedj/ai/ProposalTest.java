package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 提议的构造口径：执行工具的提议必须带齐动作分类、命令与载荷，其余步骤三者都必须为空。
 *
 * <p>提议还没落账，守卫只能靠这些字段判断能不能放行；缺字段的提议无法判断审批强度， 因此缺字段必须是异常而不是“按最宽松处理”。
 */
class ProposalTest {

    private static final UUID TARGET = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static CommandRef command() {
        return new CommandRef("ticket.create", List.of(TARGET), 7L);
    }

    @Test
    @DisplayName("EXECUTE_TOOL 缺少动作分类时拒绝")
    void execute_tool_requires_action_class() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.EXECUTE_TOOL,
                                        null,
                                        command(),
                                        Map.of("title", "t"),
                                        null,
                                        List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionClass");
    }

    @Test
    @DisplayName("EXECUTE_TOOL 缺少命令引用时拒绝")
    void execute_tool_requires_command() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.EXECUTE_TOOL,
                                        ActionClass.REVERSIBLE,
                                        null,
                                        Map.of("title", "t"),
                                        null,
                                        List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("command");
    }

    @Test
    @DisplayName("EXECUTE_TOOL 缺少载荷时拒绝")
    void execute_tool_requires_payload() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.EXECUTE_TOOL,
                                        ActionClass.REVERSIBLE,
                                        command(),
                                        null,
                                        null,
                                        List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("非 EXECUTE_TOOL 的提议不得带动作分类")
    void non_execute_tool_rejects_action_class() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.READ,
                                        ActionClass.READ_ONLY,
                                        null,
                                        null,
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTE_TOOL");
    }

    @Test
    @DisplayName("非 EXECUTE_TOOL 的提议不得带命令引用")
    void non_execute_tool_rejects_command() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.ASK_USER,
                                        null,
                                        command(),
                                        null,
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTE_TOOL");
    }

    @Test
    @DisplayName("非 EXECUTE_TOOL 的提议不得带载荷")
    void non_execute_tool_rejects_payload() {
        assertThatThrownBy(
                        () ->
                                new Proposal(
                                        DecisionKind.FINISH,
                                        null,
                                        null,
                                        Map.of("title", "t"),
                                        null,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXECUTE_TOOL");
    }

    @Test
    @DisplayName("evidence 缺省为空列表，rationale 可缺省")
    void evidence_and_rationale_default() {
        Proposal proposal = new Proposal(DecisionKind.READ, null, null, null, null, null);

        assertThat(proposal.evidence()).isEmpty();
        assertThat(proposal.rationale()).isNull();
        assertThat(proposal.actionClass()).isNull();
        assertThat(proposal.command()).isNull();
        assertThat(proposal.payload()).isNull();
    }

    @Test
    @DisplayName("步骤类型必填")
    void kind_is_mandatory() {
        assertThatThrownBy(() -> new Proposal(null, null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kind");
    }
}
