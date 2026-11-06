package cn.byteforce.seedj.infra.local.workflow;

import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskSpec;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowFailure;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** 单实例可变状态；所有读写都在 {@link #lock} 下进行。 */
final class InstanceState {

    final WorkflowInstanceId id;
    final WorkflowDefinition definition;
    final String startedBy;
    final Instant startedAt;
    final ReentrantLock lock = new ReentrantLock();

    final Map<String, String> variables = new HashMap<>();
    final Map<TaskId, HumanTask> tasks = new LinkedHashMap<>();
    final Map<TaskId, HumanTaskSpec> taskSpecs = new HashMap<>();
    final Map<TaskId, Suspension> suspensions = new HashMap<>();
    final List<AfterPending> afterPending = new ArrayList<>();
    final List<Delegation> delegations = new ArrayList<>();
    final List<WorkflowHistoryEvent> events = new ArrayList<>();
    final Set<String> actionIds = new HashSet<>();
    final Set<String> completedSteps = new LinkedHashSet<>();
    final Map<String, Instant> stepStartedAt = new HashMap<>();
    final Map<String, Instant> stepDeadline = new HashMap<>();
    final Map<TaskId, String> lastRejectTarget = new HashMap<>();

    WorkflowStatus status = WorkflowStatus.RUNNING;
    String currentStep;
    Instant updatedAt;
    Instant completedAt;
    WorkflowFailure failure;
    long version;

    InstanceState(WorkflowInstanceId id, WorkflowDefinition definition,
                  String startedBy, Instant startedAt, Instant updatedAt) {
        this.id = id;
        this.definition = definition;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.updatedAt = updatedAt;
    }

    boolean terminal() {
        return status == WorkflowStatus.COMPLETED
                || status == WorkflowStatus.FAILED
                || status == WorkflowStatus.CANCELLED
                || status == WorkflowStatus.TERMINATED;
    }
}
