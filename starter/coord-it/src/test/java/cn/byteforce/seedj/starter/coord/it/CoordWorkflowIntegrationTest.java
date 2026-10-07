package cn.byteforce.seedj.starter.coord.it;

import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.coord.sdk.workflow.WorkflowDefinition;
import cn.byteforce.coord.sdk.workflow.WorkflowDefinitionSummary;
import cn.byteforce.coord.sdk.workflow.WorkflowInstanceSummary;
import cn.byteforce.coord.sdk.workflow.WorkflowState;
import cn.byteforce.coord.sdk.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流引擎（{@code WorkflowClient}）集成验证。
 *
 * <p>覆盖：部署 CNCF Serverless Workflow DSL（YAML）→ 列举定义 → 按定义启动实例 →
 * 状态推进至 COMPLETED → 列举实例。全流程经真实 Agent 的 workflow 插件与
 * KvWorkflowStore（raft-persisted）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
class CoordWorkflowIntegrationTest extends AbstractCoordIntegrationTest {

    /**
     * 最小可自完成工作流：inject 状态写入初始数据后即结束（无外部任务派发依赖）。
     */
    private static final String SIMPLE_WORKFLOW = """
            id: it-simple
            version: "1.0"
            start: init
            states:
              - name: init
                type: inject
                data: { hello: world }
                end: true
            """;

    @Autowired
    private WorkflowClient workflowClient;

    @Test
    void deployStartAndCompleteWorkflow() {
        String namespace = uniqueName("it-wf");

        WorkflowDefinition definition = workflowClient.deployDefinition(namespace, SIMPLE_WORKFLOW);
        assertThat(definition.workflowId()).isNotBlank();
        assertThat(definition.name()).isEqualTo("it-simple");

        assertThat(workflowClient.listDefinitions(namespace))
                .extracting(WorkflowDefinitionSummary::workflowId)
                .contains(definition.workflowId());

        String instanceId = workflowClient.startByDefinition(
                definition.workflowId(), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(instanceId).isNotBlank();

        // 引擎后台推进实例状态；轮询至终态（COMPLETED 为本定义的有保证终态）
        awaitUntil("工作流实例应推进到 COMPLETED", Duration.ofSeconds(20),
                () -> workflowClient.getStatus(instanceId).state() == WorkflowState.COMPLETED);

        WorkflowStatus status = workflowClient.getStatus(instanceId);
        assertThat(status.state().isTerminal()).isTrue();
        assertThat(status.workflowId()).isEqualTo(instanceId);

        // 实例可被按 namespace 列举，且携带运行时上下文（init 注入的 hello=world）
        assertThat(workflowClient.listInstances(namespace, null, null, 20, ""))
                .extracting(WorkflowInstanceSummary::instanceId)
                .contains(instanceId);
        WorkflowInstanceSummary summary = workflowClient.listInstances(namespace, null, null, 20, "")
                .stream()
                .filter(instance -> instanceId.equals(instance.instanceId()))
                .findFirst()
                .orElseThrow();
        assertThat(summary.state()).isEqualTo("COMPLETED");
        assertThat(new String(summary.contextJson(), StandardCharsets.UTF_8)).contains("hello");
    }

    @Test
    void deployDefinitionVersionIsExposed() {
        String namespace = uniqueName("it-wf-ver");
        WorkflowDefinition definition = workflowClient.deployDefinition(namespace, SIMPLE_WORKFLOW);

        assertThat(definition.version()).isNotBlank();
        assertThat(workflowClient.getDefinition(definition.workflowId()).definitionYaml())
                .contains("it-simple");
    }
}
