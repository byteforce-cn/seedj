package cn.byteforce.seedj.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 决策意图的构造口径：步骤类型决定是否需要动作分类，动作分类决定是否允许审批快照。
 *
 * <p>两个方向都要拒绝：{@code EXECUTE_TOOL} 缺分类会让守卫无法定审批强度；非执行步骤带分类会让 两套语义混用；给不需要审批的动作挂审批快照则会让“已审批”成为可伪装的假象。
 */
class DecisionIntentTest {

    private static final UUID DECISION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TASK_CONTRACT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONVERSATION = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTOR = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private static final String ARGS_HASH = "sha256:" + "a".repeat(64);
    private static final String IDEM_FINGERPRINT = "b".repeat(64);

    /** 意图的构造脚手架：默认值全部合法，测试只改动被测组件。 */
    private static final class Fields {
        private UUID decisionId = DECISION;
        private UUID tenantId = TENANT;
        private UUID taskContractId = TASK_CONTRACT;
        private UUID conversationId = CONVERSATION;
        private int step = 0;
        private DecisionKind kind = DecisionKind.EXECUTE_TOOL;
        private ActionClass actionClass = ActionClass.REVERSIBLE;
        private CommandRef command = new CommandRef("ticket.create", List.of(TENANT), 7L);
        private String argsHash = ARGS_HASH;
        private String idemFingerprint = IDEM_FINGERPRINT;
        private Map<String, Object> args = Map.of("title", "t");
        private String rationale = "用户要求创建工单";
        private List<Evidence> evidence = List.of(new Evidence("message", "im://m-1", null));
        private ModelRef model = new ModelRef("seedj-agent", "2026-11", "prompt-v1", null);
        private PolicyRef policy = PolicyRef.none();
        private AgentIdentity identity = new AgentIdentity(ACTOR, null, null, "seedj-ai-starter");
        private ApprovalSnapshot approval;

        private DecisionIntent build() {
            return new DecisionIntent(
                    decisionId,
                    tenantId,
                    taskContractId,
                    conversationId,
                    step,
                    kind,
                    actionClass,
                    command,
                    argsHash,
                    idemFingerprint,
                    args,
                    rationale,
                    evidence,
                    model,
                    policy,
                    identity,
                    approval);
        }
    }

    @Test
    @DisplayName("EXECUTE_TOOL 必须带动作分类")
    void execute_tool_requires_action_class() {
        Fields fields = new Fields();
        fields.actionClass = null;

        assertThatThrownBy(fields::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actionClass");
    }

    @Test
    @DisplayName("非 EXECUTE_TOOL 步骤不得带动作分类")
    void non_execute_tool_rejects_action_class() {
        Fields fields = new Fields();
        fields.kind = DecisionKind.ASK_USER;

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionClass");
    }

    @Test
    @DisplayName("不需要审批的动作带了审批快照会被拒绝")
    void approval_is_rejected_for_reversible_action() {
        Fields fields = new Fields();
        fields.approval = approved();

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval");
    }

    @Test
    @DisplayName("没有动作分类的步骤带了审批快照同样被拒绝")
    void approval_is_rejected_when_action_class_is_absent() {
        Fields fields = new Fields();
        fields.kind = DecisionKind.ESCALATE;
        fields.actionClass = null;
        fields.approval = approved();

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approval");
    }

    @Test
    @DisplayName("不可逆动作允许携带已批准的审批快照")
    void irreversible_action_accepts_approval() {
        Fields fields = new Fields();
        fields.actionClass = ActionClass.IRREVERSIBLE_FINANCIAL;
        fields.approval = approved();

        DecisionIntent intent = fields.build();

        assertThat(intent.actionClass()).isEqualTo(ActionClass.IRREVERSIBLE_FINANCIAL);
        assertThat(intent.approval()).isSameAs(fields.approval);
    }

    @ParameterizedTest
    @ValueSource(strings = {"argsHash", "idemFingerprint", "rationale"})
    @DisplayName("必填文本字段为空白时按字段名拒绝")
    void blank_text_component_is_rejected(String component) {
        Fields fields = new Fields();
        switch (component) {
            case "argsHash" -> fields.argsHash = " ";
            case "idemFingerprint" -> fields.idemFingerprint = "";
            case "rationale" -> fields.rationale = " ";
            default -> throw new IllegalArgumentException("unknown component: " + component);
        }

        assertThatThrownBy(fields::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(component);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "decisionId",
                "tenantId",
                "taskContractId",
                "conversationId",
                "kind",
                "command",
                "model",
                "identity"
            })
    @DisplayName("必填组件缺失时按组件名拒绝")
    void mandatory_component_is_rejected(String component) {
        Fields fields = new Fields();
        switch (component) {
            case "decisionId" -> fields.decisionId = null;
            case "tenantId" -> fields.tenantId = null;
            case "taskContractId" -> fields.taskContractId = null;
            case "conversationId" -> fields.conversationId = null;
            case "kind" -> fields.kind = null;
            case "command" -> fields.command = null;
            case "model" -> fields.model = null;
            case "identity" -> fields.identity = null;
            default -> throw new IllegalArgumentException("unknown component: " + component);
        }

        assertThatThrownBy(fields::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining(component);
    }

    @Test
    @DisplayName("args 与 evidence 缺省为空集合")
    void args_and_evidence_default_to_empty() {
        Fields fields = new Fields();
        fields.args = null;
        fields.evidence = null;

        DecisionIntent intent = fields.build();

        assertThat(intent.args()).isEmpty();
        assertThat(intent.evidence()).isEmpty();
    }

    @Test
    @DisplayName("分区由租户派生")
    void partition_is_derived_from_tenant() {
        assertThat(new Fields().build().partition()).isEqualTo(DecisionRecord.partitionOf(TENANT));
    }

    private static ApprovalSnapshot approved() {
        return new ApprovalSnapshot(
                "fixture-instance",
                "fixture-task",
                ApprovalStatus.APPROVED,
                NOW,
                ACTOR,
                NOW,
                ARGS_HASH,
                3L);
    }
}
