package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.byteforce.seedj.workflow.spi.AddAssigneePosition;
import cn.byteforce.seedj.workflow.spi.AllApprove;
import cn.byteforce.seedj.workflow.spi.AnyRejectTerminates;
import cn.byteforce.seedj.workflow.spi.AtLeastPercentage;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RejectCompletion;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.SignCompletion;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkflowDefinitionCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final WorkflowDefinitionCompiler compiler =
            new WorkflowDefinitionCompiler(new CoordWorkflowIds("test"), "sign-admin", 64 * 1024);

    @Test
    void compilesApproveNodeAndKeepsDefinitionInTags() throws Exception {
        WorkflowDefinition definition = approvalDefinition(Set.of("start"), Map.of());

        String json = compiler.compile(definition);
        JsonNode root = MAPPER.readTree(json);

        JsonNode approve = root.get("do").get(1).get("review").get("approve");
        assertThat(approve.get("approvers")).hasSize(2);
        assertThat(approve.get("approvers").get(0).stringValue()).isEqualTo("u1");
        assertThat(approve.get("approvers").get(1).stringValue()).isEqualTo("u2");
        assertThat(approve.get("mode").stringValue()).isEqualTo("all");
        assertThat(approve.get("reject").stringValue()).isEqualTo("anyReject");
        assertThat(approve.get("deadline").stringValue()).isEqualTo("PT30S");
        assertThat(approve.get("timeoutPolicy").stringValue()).isEqualTo("waiting");
        assertThat(approve.get("signAdmins").get(0).stringValue()).isEqualTo("sign-admin");
        assertThat(approve.get("onReject").stringValue()).isEqualTo("start");
        // 审批节点的步骤 timeout 也要写进 task_meta：coord 的 ResumeWorkflow 时间预算用它。
        assertThat(root.get("do").get(1).get("review").get("timeout").get("after").stringValue())
                .isEqualTo("PT30S");
        assertThat(root.get("document").get("namespace").stringValue())
                .isEqualTo("test:workflow:contract");
        assertThat(root.get("document").get("version").stringValue()).isEqualTo("1.0");

        String embedded = root.get("document").get("tags").get("seedj.definition").stringValue();
        assertThat(WorkflowDefinitionCodec.fromJson(embedded)).isEqualTo(definition);
        assertThat(compiler.compile(definition)).isEqualTo(json);
    }

    @Test
    void ratioSignCompletionIsReducedToTheSimplestFraction() throws Exception {
        WorkflowDefinition definition =
                definition(
                        List.of(
                                activity("start"),
                                human(
                                        "review",
                                        spec(
                                                List.of("u1", "u2"),
                                                new AtLeastPercentage(0.5),
                                                null,
                                                Set.of(),
                                                null,
                                                Map.of()),
                                        Duration.ofSeconds(30))));
        JsonNode root = MAPPER.readTree(compiler.compile(definition));
        assertThat(root.get("do").get(1).get("review").get("approve").get("mode").stringValue())
                .isEqualTo("ratio:1/2");
    }

    @Test
    void multipleForwardRejectTargetsFailClosedAtRegisterTime() {
        WorkflowDefinition definition =
                definition(
                        List.of(
                                activity("start"),
                                human(
                                        "review",
                                        spec(
                                                List.of("u1"),
                                                new AllApprove(),
                                                new AnyRejectTerminates(),
                                                Set.of("first", "second"),
                                                null,
                                                Map.of()),
                                        Duration.ofSeconds(30)),
                                activity("first"),
                                activity("second")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("coord#382");
    }

    @Test
    void unknownTimeoutPolicyFailsClosedAtRegisterTime() {
        WorkflowDefinition definition =
                approvalDefinition(Set.of("start"), Map.of("timeoutPolicy", "someday"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("timeoutPolicy");
    }

    @Test
    void rejectTargetMustExist() {
        WorkflowDefinition definition = approvalDefinition(Set.of("missing"), Map.of());

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("rejectTargets");
    }

    @Test
    void oversizedEmbeddedDefinitionFailsClosed() {
        WorkflowDefinitionCompiler tiny =
                new WorkflowDefinitionCompiler(new CoordWorkflowIds("test"), "sign-admin", 1024);
        WorkflowDefinition definition = approvalDefinition(Set.of("start"), Map.of());

        assertThatThrownBy(() -> tiny.compile(definition))
                .isInstanceOf(WorkflowProviderException.class)
                .hasMessageContaining("exceeds 1024 bytes");
    }

    private WorkflowDefinition approvalDefinition(
            Set<String> rejectTargets, Map<String, String> metadata) {
        Map<String, String> effective = new java.util.TreeMap<>(metadata);
        effective.putIfAbsent("timeoutPolicy", "waiting");
        return definition(
                List.of(
                        activity("start"),
                        human(
                                "review",
                                spec(
                                        List.of("u1", "u2"),
                                        new AllApprove(),
                                        new AnyRejectTerminates(),
                                        rejectTargets,
                                        Duration.ofSeconds(30),
                                        effective),
                                Duration.ofSeconds(30)),
                        human(
                                "final",
                                spec(
                                        List.of("u3"),
                                        new AllApprove(),
                                        null,
                                        Set.of(),
                                        null,
                                        Map.of()),
                                Duration.ofSeconds(30))));
    }

    private static WorkflowDefinition definition(List<StepDefinition> steps) {
        return new WorkflowDefinition(
                new WorkflowDefinitionId("contract", "approval"),
                new WorkflowDefinitionVersion(1, 0),
                steps,
                Duration.ofSeconds(30),
                null,
                Map.of());
    }

    private static HumanTaskSpec spec(
            List<String> users,
            SignCompletion signCompletion,
            RejectCompletion rejectCompletion,
            Set<String> rejectTargets,
            Duration dueIn,
            Map<String, String> metadata) {
        return new HumanTaskSpec(
                users.stream()
                        .map(user -> new ParticipantSpec(ParticipantType.USER, user, Map.of()))
                        .toList(),
                signCompletion,
                rejectCompletion,
                rejectTargets,
                null,
                Map.of(),
                false,
                Set.<AddAssigneePosition>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                dueIn,
                metadata);
    }

    private static StepDefinition human(
            String name, HumanTaskSpec spec, Duration timeout) {
        return new StepDefinition(
                name,
                StepKind.HUMAN_TASK,
                timeout,
                RetryPolicy.noRetry(),
                List.of(),
                Map.of(),
                spec,
                null);
    }

    private static StepDefinition activity(String name) {
        return new StepDefinition(
                name,
                StepKind.ACTIVITY,
                Duration.ofSeconds(30),
                RetryPolicy.noRetry(),
                List.of(),
                Map.of());
    }
}
