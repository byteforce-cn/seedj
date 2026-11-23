package cn.byteforce.seedj.infra.coord.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;
import cn.byteforce.seedj.workflow.spi.ActionRequest;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.RetryPolicy;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.StepDefinition;
import cn.byteforce.seedj.workflow.spi.StepKind;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 真集群冒烟：装配、部署、启动、审批、读面。 */
class CoordWorkflowSmokeTest {

    private static CoordCluster cluster;

    @BeforeAll
    static void start() {
        cluster = CoordCluster.startWithAuth();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void singleApproverCompletes() {
        CoordClient client = cluster.client();
        CoordWorkflowProperties properties = new CoordWorkflowProperties();
        properties.setSignAdmin("root");
        CoordWorkflowProvider provider =
                new CoordWorkflowProvider(
                        client.workflow(), client.kv(), properties, "smoke-" + UUID.randomUUID());

        HumanTaskSpec spec =
                new HumanTaskSpec(
                        List.of(new ParticipantSpec(ParticipantType.USER, "u1", Map.of())),
                        new cn.byteforce.seedj.workflow.spi.AllApprove(),
                        null,
                        java.util.Set.of(),
                        null,
                        Map.of(),
                        false,
                        java.util.Set.of(),
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null,
                        Map.of());
        WorkflowDefinition definition =
                new WorkflowDefinition(
                        new WorkflowDefinitionId("contract", "smoke"),
                        new WorkflowDefinitionVersion(1, 0),
                        List.of(
                                new StepDefinition(
                                        "review",
                                        StepKind.HUMAN_TASK,
                                        Duration.ofSeconds(30),
                                        RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of(),
                                        spec,
                                        null),
                                new StepDefinition(
                                        "done",
                                        StepKind.ACTIVITY,
                                        Duration.ofSeconds(30),
                                        RetryPolicy.noRetry(),
                                        List.of(),
                                        Map.of())),
                        Duration.ofSeconds(30),
                        null,
                        Map.of());
        String dsl =
                new WorkflowDefinitionCompiler(new CoordWorkflowIds("probe"), "root", 64 * 1024)
                        .compile(definition);
        System.out.println("=== DSL " + dsl);
        provider.register(definition);

        WorkflowInstanceId id = new WorkflowInstanceId("wf-" + UUID.randomUUID());
        WorkflowInstance started =
                provider.start(
                        new StartWorkflowRequest(
                                id,
                                definition.id(),
                                definition.version(),
                                "starter",
                                Map.of("k", "v")));
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var raw = client.workflow().getStatus(id.value());
        System.out.println(
                "=== RAW state="
                        + raw.state()
                        + " current="
                        + raw.currentStateName()
                        + " approval="
                        + raw.approvalStatus()
                        + " stack="
                        + raw.taskStack());
        System.out.println("=== HISTORY " + provider.history(id));
        assertThat(started.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(started.currentStep()).isEqualTo("review");
        assertThat(started.startedBy()).isEqualTo("starter");
        assertThat(started.variables()).containsEntry("k", "v");

        var detail = client.workflow().getInstanceDetail(id.value());
        System.out.println(
                "=== DETAIL rounds="
                        + detail.rounds().size()
                        + " tasks="
                        + detail.tasks().size()
                        + " branches="
                        + detail.branches());
        var groups =
                client.workflow()
                        .listTaskGroups(
                                new cn.byteforce.coord.sdk.workflow.TaskQueryFilter(
                                        id.value(), null, null, null, null, null, null),
                                200,
                                "");
        System.out.println("=== GROUPS " + groups.groups());
        List<HumanTask> tasks =
                provider.listTasks(
                        new TaskQuery(
                                id,
                                null,
                                null,
                                List.of(),
                                null,
                                cn.byteforce.seedj.workflow.spi.HumanTaskStatus.PENDING,
                                null,
                                100,
                                null));
        assertThat(tasks).hasSize(1);
        HumanTask task = tasks.get(0);
        assertThat(task.kind()).isEqualTo(cn.byteforce.seedj.workflow.spi.HumanTaskKind.ORIGINAL);
        assertThat(task.assignments()).hasSize(1);

        var preDriver =
                client.kv()
                        .range(
                                ("/_workflow/v3/drivers/" + id.value())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println(
                "=== PRE DRIVER "
                        + preDriver.kvs().stream()
                                .map(kv -> kv.leaseId() + "/" + kv.version())
                                .toList());
        WorkflowInstance completed =
                provider.act(
                        new ActionRequest(
                                id,
                                new ApproveAction(
                                        UUID.randomUUID().toString(),
                                        task.id(),
                                        "u1",
                                        "ok",
                                        Map.of(),
                                        Instant.now())));
        for (int i = 0; i < 30; i++) {
            var st = client.workflow().getStatus(id.value());
            var hist = client.workflow().history(id.value());
            System.out.println(
                    "=== P "
                            + i * 100
                            + " state="
                            + st.state()
                            + " current="
                            + st.currentStateName()
                            + " approval="
                            + st.approvalStatus()
                            + " updated="
                            + st.updatedAt()
                            + " lastEvent="
                            + (hist.isEmpty() ? "-" : hist.get(hist.size() - 1).eventType()));
            if (st.state().isTerminal()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println(
                "=== AFTER ACT status="
                        + completed.status()
                        + " current="
                        + completed.currentStep()
                        + " pending="
                        + completed.pendingTaskIds()
                        + " raw="
                        + client.workflow().getStatus(id.value()).state()
                        + " approval="
                        + client.workflow().getStatus(id.value()).approvalStatus());
        for (int i = 0; i < 40; i++) {
            var seen =
                    client.kv()
                            .range(
                                    ("/_workflow/v3/drivers/" + id.value())
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!seen.kvs().isEmpty()) {
                System.out.println(
                        "=== DRIVE SEEN at " + i * 50 + "ms " + seen.kvs().get(0).leaseId());
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            var driver =
                    client.kv()
                            .range(
                                    ("/_workflow/v3/drivers/" + id.value())
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println(
                    "=== DRIVER "
                            + driver.kvs().stream()
                                    .map(
                                            kv ->
                                                    new String(
                                                                    kv.key(),
                                                                    java.nio.charset
                                                                            .StandardCharsets.UTF_8)
                                                            + " lease="
                                                            + kv.leaseId()
                                                            + " ver="
                                                            + kv.version())
                                    .toList());
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < 20; i++) {
            var st = client.workflow().getStatus(id.value());
            System.out.println(
                    "=== TICK "
                            + i * 500
                            + " state="
                            + st.state()
                            + " current="
                            + st.currentStateName()
                            + " history="
                            + provider.history(id).stream().map(e -> e.eventType()).toList());
            if (st.state().isTerminal()) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(provider.history(id)).isNotEmpty();
    }
}
