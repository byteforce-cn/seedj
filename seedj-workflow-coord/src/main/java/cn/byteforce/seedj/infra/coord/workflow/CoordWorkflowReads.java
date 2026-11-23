package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.coord.sdk.workflow.ApprovalAssignment;
import cn.byteforce.coord.sdk.workflow.ApprovalBranch;
import cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail;
import cn.byteforce.coord.sdk.workflow.ApprovalRound;
import cn.byteforce.coord.sdk.workflow.ApprovalTask;
import cn.byteforce.coord.sdk.workflow.ApprovalVote;
import cn.byteforce.coord.sdk.workflow.TaskGroup;
import cn.byteforce.coord.sdk.workflow.TaskGroupPage;
import cn.byteforce.coord.sdk.workflow.TaskQueryFilter;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.coord.sdk.workflow.WorkflowFault;
import cn.byteforce.coord.sdk.workflow.WorkflowInstanceSummary;
import cn.byteforce.coord.sdk.workflow.WorkflowState;
import cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowSidecar.ServerClock;
import cn.byteforce.seedj.workflow.spi.ActionDecision;
import cn.byteforce.seedj.workflow.spi.ApproveRecord;
import cn.byteforce.seedj.workflow.spi.AssignmentStatus;
import cn.byteforce.seedj.workflow.spi.HumanTask;
import cn.byteforce.seedj.workflow.spi.HumanTaskKind;
import cn.byteforce.seedj.workflow.spi.HumanTaskStatus;
import cn.byteforce.seedj.workflow.spi.ParticipantSpec;
import cn.byteforce.seedj.workflow.spi.ParticipantType;
import cn.byteforce.seedj.workflow.spi.TaskAssignment;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.TaskQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionVersion;
import cn.byteforce.seedj.workflow.spi.WorkflowFailure;
import cn.byteforce.seedj.workflow.spi.WorkflowHistoryEvent;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowSearchQuery;
import cn.byteforce.seedj.workflow.spi.WorkflowStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 读面：coord 的 round/seat/assignment 投影成 seedj 的实例 / HumanTask / history。
 *
 * <p>seedj 的 {@code TaskId} 是 coord 的 round id；seat 只是 assignment。coord 0.4.5 起
 * 撤回投影与多值状态过滤已由服务端提供（#451/#452），本类不再做客户端补偿投影。
 */
final class CoordWorkflowReads {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Comparator<HumanTask> TASK_ORDER =
            Comparator.comparing(HumanTask::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(task -> task.id().value());

    private final WorkflowClient workflows;
    private final CoordWorkflowIds ids;
    private final CoordWorkflowDefinitions definitions;
    private final CoordWorkflowSidecar sidecar;
    private final ServerClock clock;
    private final Map<String, Optional<WorkflowDefinition>> definitionCache =
            new ConcurrentHashMap<>();

    CoordWorkflowReads(
            WorkflowClient workflows,
            CoordWorkflowIds ids,
            CoordWorkflowDefinitions definitions,
            CoordWorkflowSidecar sidecar,
            ServerClock clock) {
        this.workflows = workflows;
        this.ids = ids;
        this.definitions = definitions;
        this.sidecar = sidecar;
        this.clock = clock;
    }

    // ──── 实例 ────

    WorkflowInstance describe(WorkflowInstanceId instanceId) {
        Snapshot snapshot = snapshot(instanceId);
        return instance(snapshot.status(), snapshot.detail(), pendingRoundIds(snapshot.status()));
    }

    Snapshot snapshot(WorkflowInstanceId instanceId) {
        try {
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status =
                    workflows.getStatus(instanceId.value());
            clock.observe(status.updatedAt());
            clock.observe(status.createdAt());
            ApprovalInstanceDetail detail = detailOrNull(instanceId.value());
            return new Snapshot(status, detail);
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new WorkflowNotFoundException("workflow instance not found: " + instanceId.value());
            }
            throw e;
        }
    }

    List<WorkflowInstance> search(WorkflowSearchQuery query) {
        String namespace = null;
        String definition = null;
        if (query.definitionId() != null) {
            namespace = ids.coordNamespace(query.definitionId().namespace());
            definition = ids.coordName(query.definitionId());
        }
        List<String> statuses = coordStatuses(query.status());
        long startedAfter = query.startedAfter() == null ? 0 : query.startedAfter().toEpochMilli();
        long startedBefore = query.startedBefore() == null ? 0 : query.startedBefore().toEpochMilli();
        int offset = cursorOffset(query.cursor());
        List<WorkflowInstance> matches = new ArrayList<>();
        String token = "";
        int fetched = 0;
        while (matches.size() < query.limit()) {
            var page =
                    workflows.listInstances(
                            namespace,
                            definition,
                            statuses,
                            startedAfter,
                            startedBefore,
                            Math.min(200, Math.max(1, query.limit())),
                            token);
            clock.observe(pageNext(page));
            for (WorkflowInstanceSummary summary : page.instances()) {
                if (fetched++ < offset) {
                    continue;
                }
                WorkflowInstance instance = instance(summary);
                if (matchesSeedjStatus(instance, query.status())) {
                    matches.add(instance);
                    if (matches.size() >= query.limit()) {
                        break;
                    }
                }
            }
            token = page.nextPageToken();
            if (token == null || token.isEmpty()) {
                break;
            }
        }
        return matches;
    }

    private long pageNext(cn.byteforce.coord.sdk.workflow.InstancePage page) {
        return page.instances().stream()
                .mapToLong(WorkflowInstanceSummary::updatedAt)
                .max()
                .orElse(0);
    }

    private WorkflowInstance instance(WorkflowInstanceSummary summary) {
        String coordNamespace = summary.definitionNamespace();
        String name = summary.definitionName();
        WorkflowDefinitionId definitionId;
        WorkflowDefinitionVersion version;
        try {
            definitionId =
                    coordNamespace == null || coordNamespace.isBlank()
                            ? new WorkflowDefinitionId("", name == null ? "" : name)
                            : definitions.definitionIdOf(coordNamespace, name);
            version = ids.seedjVersion(summary.definitionVersion());
        } catch (WorkflowProviderException e) {
            definitionId = new WorkflowDefinitionId("", name == null ? "" : name);
            version = new WorkflowDefinitionVersion(0, 0);
        }
        boolean terminal = isTerminal(summary.state());
        Map<String, String> variables = new LinkedHashMap<>();
        if (summary.contextJson() != null && summary.contextJson().length > 0) {
            variables.putAll(variables(new String(summary.contextJson(), StandardCharsets.UTF_8)));
        }
        return new WorkflowInstance(
                new WorkflowInstanceId(summary.instanceId()),
                definitionId,
                version,
                status(summary.state(), ""),
                null,
                List.of(),
                summary.initiator() == null ? "" : summary.initiator(),
                instant(summary.startedAt()),
                instant(summary.updatedAt()),
                terminal ? instant(summary.completedAt() > 0 ? summary.completedAt() : summary.updatedAt()) : null,
                variables,
                null);
    }

    private WorkflowInstance instance(
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status,
            ApprovalInstanceDetail detail,
            List<TaskId> pendingTasks) {
        WorkflowDefinitionId definitionId =
                definitions.definitionIdOf(
                        status.definitionNamespace(),
                        status.definitionName() == null ? "" : status.definitionName());
        WorkflowDefinitionVersion version = ids.seedjVersion(status.definitionVersion());
        WorkflowStatus seedjStatus =
                status(status.state(), status.approvalStatus(), status.fault());
        boolean terminal = isTerminal(seedjStatus);
        String currentStep = terminal ? null : currentStep(status, detail);
        return new WorkflowInstance(
                new WorkflowInstanceId(status.workflowId()),
                definitionId,
                version,
                seedjStatus,
                currentStep,
                pendingTasks,
                detail == null ? "" : detail.initiator(),
                instant(status.createdAt()),
                instant(status.updatedAt()),
                terminal ? instant(status.updatedAt()) : null,
                variables(new String(status.input(), StandardCharsets.UTF_8)),
                failure(status, currentStep));
    }

    /**
     * 当前步骤：GetStatus 走本地缓存投影（驱动推进后 2–3s 内可能仍报旧节点），
     * GetInstanceDetail 直接读存储是权威读面。存在 open 分支且缓存状态落后时以分支节点为准，
     * 让动作返回的快照与刚创建的轮次一致（例如驳回回退后重新进入审批节点）。
     */
    private static String currentStep(
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status,
            ApprovalInstanceDetail detail) {
        String reported = blankToNull(status.currentStateName());
        String openNode = openBranchNode(detail);
        if (openNode != null && !openNode.equals(reported)) {
            return openNode;
        }
        return reported;
    }

    private static String openBranchNode(ApprovalInstanceDetail detail) {
        if (detail == null) {
            return null;
        }
        for (ApprovalBranch branch : detail.branches()) {
            if ("open".equalsIgnoreCase(branch.status())) {
                return blankToNull(branch.node());
            }
        }
        return null;
    }

    private List<TaskId> pendingRoundIds(cn.byteforce.coord.sdk.workflow.WorkflowStatus status) {
        TaskGroupPage page;
        try {
            page =
                    workflows.listTaskGroups(
                        new TaskQueryFilter(
                                status.workflowId(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of("created", "claimed"),
                                List.of()),
                            200,
                            "");
        } catch (CoordException e) {
            // 审批聚合尚未打开（实例刚启动）：没有待办，不是实例不存在。
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return List.of();
            }
            throw e;
        }
        Map<String, String> deferred =
                deferredAfterPredecessors(status.workflowId());
        List<TaskId> pending = new ArrayList<>();
        for (TaskGroup group : page.groups()) {
            if ("scheduled".equalsIgnoreCase(group.status())
                    && !deferredAfter(group, deferred)) {
                continue;
            }
            pending.add(new TaskId(group.roundId()));
        }
        return pending;
    }

    // ──── 任务 ────

    Optional<HumanTask> describeTask(TaskId taskId) {
        String instanceId = ids.instanceIdOf(taskId);
        ApprovalInstanceDetail detail = detailOrNull(instanceId);
        List<TaskGroup> groups = groups(instanceId);
        TaskGroup group =
                groups.stream()
                        .filter(candidate -> candidate.roundId().equals(taskId.value()))
                        .findFirst()
                        .orElse(null);
        Map<String, String> deferred = deferredAfterPredecessors(instanceId);
        if (group == null
                || ("scheduled".equalsIgnoreCase(group.status())
                        && !deferredAfter(group, deferred))) {
            return Optional.empty();
        }
        return Optional.of(task(detail, group, seatRounds(groups), deferred));
    }

    List<HumanTask> listTasks(TaskQuery query) {
        List<HumanTask> matched = new ArrayList<>();
        if (query.instanceId() != null) {
            ApprovalInstanceDetail detail = detailOrNull(query.instanceId().value());
            List<TaskGroup> groups = groups(query.instanceId().value());
            if (System.getProperty("workflow.debug") != null) {
                System.out.println(
                        "DEBUG listTasks groups="
                                + groups.size()
                                + " statuses="
                                + groups.stream()
                                        .map(g -> g.roundId() + ":" + g.status())
                                        .toList());
            }
            Map<String, String> seatRounds = seatRounds(groups);
            Map<String, String> deferred = deferredAfterPredecessors(query.instanceId().value());
            for (TaskGroup group : groups) {
                if (hidden(group, deferred)) {
                    continue;
                }
                HumanTask task = task(detail, group, seatRounds, deferred);
                if (matches(query, task)) {
                    matched.add(task);
                }
            }
        } else {
            matched.addAll(listTasksAcrossInstances(query));
        }
        if (System.getProperty("workflow.debug") != null) {
            System.out.println(
                    "DEBUG matches="
                            + matched.stream()
                                    .map(t -> t.id().value() + ":" + t.status() + ":" + t.kind())
                                    .toList());
        }
        matched.sort(TASK_ORDER);
        int offset = cursorOffset(query.cursor());
        return matched.stream().skip(offset).limit(query.limit()).toList();
    }

    private List<HumanTask> listTasksAcrossInstances(TaskQuery query) {
        List<HumanTask> candidates = new ArrayList<>();
        String token = "";
        do {
            TaskGroupPage page =
                    workflows.listTaskGroups(
                            new TaskQueryFilter(
                                    null,
                                    null,
                                    null,
                                    null,
                                    query.stepName(),
                                    null,
                                    query.dueBefore() == null
                                            ? null
                                            : query.dueBefore().toEpochMilli(),
                                    List.of(),
                                    List.of()),
                            Math.min(200, Math.max(1, query.limit())),
                            token);
            Map<String, List<TaskGroup>> byInstance = new java.util.LinkedHashMap<>();
            for (TaskGroup group : page.groups()) {
                byInstance.computeIfAbsent(group.instanceId(), ignored -> new ArrayList<>())
                        .add(group);
            }
            for (var entry : byInstance.entrySet()) {
                ApprovalInstanceDetail detail = detailOrNull(entry.getKey());
                if (detail == null) {
                    continue;
                }
                List<TaskGroup> instanceGroups = groups(entry.getKey());
                Map<String, String> seatRounds = seatRounds(instanceGroups);
                Map<String, String> deferred = deferredAfterPredecessors(entry.getKey());
                Map<String, TaskGroup> wanted = new HashMap<>();
                for (TaskGroup group : instanceGroups) {
                    wanted.put(group.roundId(), group);
                }
                for (TaskGroup group : entry.getValue()) {
                    if (hidden(group, deferred)) {
                        continue;
                    }
                    TaskGroup full = wanted.getOrDefault(group.roundId(), group);
                    HumanTask task = task(detail, full, seatRounds, deferred);
                    if (matches(query, task)) {
                        candidates.add(task);
                    }
                }
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty() && candidates.size() < query.limit() * 4);
        return candidates;
    }

    private static boolean hidden(TaskGroup group, Map<String, String> deferred) {
        return ("scheduled".equalsIgnoreCase(group.status())
                        && !deferredAfter(group, deferred))
                || "suspended".equalsIgnoreCase(group.status());
    }

    /**
     * sidecar 里记下「原始任务已完成」的后加签：coord 0.4.5 只能在 open 分支上创建 AFTER
     * 轮次，适配器把派生轮次挂在当前分支并在 sidecar 写真正的原始任务 id。读面据此把
     * scheduled 轮次投影成待办（直到 coord 支持已完成任务的后加签）。
     */
    private Map<String, String> deferredAfterPredecessors(String instanceId) {
        Map<String, String> byAction = new HashMap<>();
        for (var entry : sidecar.actions(instanceId).entrySet()) {
            String predecessor = entry.getValue().get("completedAnchorTaskId");
            if (predecessor != null && !predecessor.isBlank()) {
                byAction.put(entry.getKey(), predecessor);
            }
        }
        return byAction;
    }

    private static boolean deferredAfter(TaskGroup group, Map<String, String> deferred) {
        if (!"scheduled".equalsIgnoreCase(group.status()) || deferred.isEmpty()) {
            return false;
        }
        for (ApprovalTask seat : group.tasks()) {
            String actionId = seat.spawnedByActionId();
            if (actionId != null && deferred.containsKey(actionId)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> seatRounds(List<TaskGroup> groups) {
        Map<String, String> seatRounds = new HashMap<>();
        for (TaskGroup group : groups) {
            for (ApprovalTask seat : group.tasks()) {
                seatRounds.put(seat.id(), group.roundId());
            }
        }
        return seatRounds;
    }

    private List<TaskGroup> groups(String instanceId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                return groupsOnce(instanceId);
            } catch (CoordException e) {
                if (e.getErrorCode() != ErrorCode.NOT_FOUND || System.nanoTime() > deadline) {
                    if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                        return List.of();
                    }
                    throw e;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return List.of();
                }
            }
        }
    }

    private List<TaskGroup> groupsOnce(String instanceId) {
        List<TaskGroup> groups = new ArrayList<>();
        String token = "";
        do {
            TaskGroupPage page =
                    workflows.listTaskGroups(
                            new TaskQueryFilter(
                                    instanceId,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            200,
                            token);
            groups.addAll(page.groups());
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return groups;
    }

    private boolean matches(TaskQuery query, HumanTask task) {
        if (query.stepName() != null && !query.stepName().equals(task.stepName())) {
            return false;
        }
        if (query.status() != null && task.status() != query.status()) {
            return false;
        }
        if (query.dueBefore() != null
                && (task.dueAt() == null || task.dueAt().isAfter(query.dueBefore()))) {
            return false;
        }
        if (query.pendingFor() != null
                && task.assignments().stream()
                        .noneMatch(
                                assignment ->
                                        assignment.status() == AssignmentStatus.PENDING
                                                && assignment
                                                        .participant()
                                                        .value()
                                                        .equals(query.pendingFor()))) {
            return false;
        }
        if (query.completedBy() != null
                && task.assignments().stream()
                        .map(TaskAssignment::record)
                        .filter(java.util.Objects::nonNull)
                        .noneMatch(
                                record ->
                                        query.completedBy().equals(record.assignee())
                                                || query.completedBy()
                                                        .equals(record.onBehalfOf()))) {
            return false;
        }
        if (!query.candidateGroups().isEmpty()) {
            boolean matched =
                    task.assignments().stream()
                            .filter(assignment -> assignment.status() == AssignmentStatus.PENDING)
                            .map(TaskAssignment::participant)
                            .filter(
                                    participant ->
                                            participant.type() == ParticipantType.GROUP
                                                    || participant.type()
                                                            == ParticipantType.DEPARTMENT)
                            .anyMatch(
                                    participant ->
                                            query.candidateGroups()
                                                    .contains(participant.value()));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    HumanTask task(
            ApprovalInstanceDetail detail,
            TaskGroup group,
            Map<String, String> seatRounds,
            Map<String, String> deferred) {
        List<ApprovalTask> seats = group.tasks();
        List<TaskAssignment> assignments = new ArrayList<>();
        for (ApprovalTask seat : seats) {
            for (ApprovalAssignment assignment : seat.assignments()) {
                assignments.add(assignment(detail, group, assignment, seat));
            }
        }
        ApprovalTask first = seats.isEmpty() ? null : seats.get(0);
        List<ApprovalTask> primarySeats = seats.stream().filter(seat -> !derived(seat)).toList();
        boolean derivedRound = !seats.isEmpty() && primarySeats.isEmpty();
        ApprovalTask derived =
                derivedRound
                        ? seats.stream()
                                .filter(seat -> seat.predecessorTaskId() != null
                                        && !seat.predecessorTaskId().isBlank())
                                .findFirst()
                                .orElse(first)
                        : null;
        ApprovalTask kindSource = derivedRound ? derived : primarySeats.stream().findFirst().orElse(first);
        HumanTaskKind kind = kind(kindSource);
        String seedjStep = group.node();
        String deferredPredecessor = null;
        for (ApprovalTask seat : seats) {
            String actionId = seat.spawnedByActionId();
            if (actionId != null && deferred.containsKey(actionId)) {
                deferredPredecessor = deferred.get(actionId);
                break;
            }
        }
        TaskId predecessor =
                deferredPredecessor != null
                        ? new TaskId(deferredPredecessor)
                        : derived == null
                                ? null
                                : new TaskId(
                                        seatRounds.getOrDefault(
                                                derived.predecessorTaskId(), group.roundId()));
        TaskId successor =
                first == null || first.successorTaskId() == null
                        || first.successorTaskId().isBlank()
                        ? null
                        : new TaskId(
                                seatRounds.getOrDefault(first.successorTaskId(), group.roundId()));
        Instant createdAt = instant(group.createdAtMs());
        Instant updatedAt = maxInstant(seats.stream().mapToLong(ApprovalTask::updatedAtMs));
        Instant completedAt = maxInstant(seats.stream().mapToLong(ApprovalTask::completedAtMs));
        Instant dueAt = instant(group.deadlineMs());
        String spawnedBy =
                derivedRound && derived != null ? blankToNull(derived.spawnedByActionId()) : null;
        return new HumanTask(
                new TaskId(group.roundId()),
                new WorkflowInstanceId(group.instanceId()),
                seedjStep == null ? "" : seedjStep,
                kind,
                status(detail, group, seats),
                assignments,
                sidecar.form(group.instanceId(), group.roundId()),
                predecessor,
                successor,
                spawnedBy,
                createdAt,
                updatedAt,
                completedAt,
                dueAt,
                metadata(detail, seedjStep));
    }

    private TaskAssignment assignment(
            ApprovalInstanceDetail detail,
            TaskGroup group,
            ApprovalAssignment assignment,
            ApprovalTask seat) {
        ParticipantSpec participant =
                new ParticipantSpec(ParticipantType.USER, assignment.principal(), Map.of());
        ApprovalVote vote = assignment.vote();
        boolean voteActor = vote != null && assignment.principal().equals(vote.actor());
        AssignmentStatus status =
                voteActor
                        ? AssignmentStatus.COMPLETED
                        : assignmentStatus(assignment.status(), vote != null);
        boolean openSeat = "open".equalsIgnoreCase(group.status());
        String reason = blankToNull(assignment.resolvedReason());
        if (status == AssignmentStatus.CANCELLED && withdrawInstanceRecorded(detail, group)) {
            reason = "WITHDRAWN";
        }
        if (status == AssignmentStatus.CANCELLED
                && "TASK_CANCELLED".equals(reason)
                && sidecar.removed(
                        group.instanceId(), group.roundId(), assignment.principal())) {
            reason = "REMOVED";
        }
        if (openSeat
                && status == AssignmentStatus.CANCELLED
                && assignment.principal().equals(seat.assignee())) {
            if ("DELEGATION_EXPIRED".equals(reason)) {
                // 委派到期：原审批人恢复可批（coord 的 assignment 投影把 from 也标成 cancelled）。
                status = AssignmentStatus.PENDING;
                reason = null;
            } else if ("RULE_SATISFIED".equals(reason)) {
                // 被委派人已投票：coord 在该席位完成时撤销代理权，但轮次仍需其他席位，
                // seedj 语义下原责任人的 assignment 仍是 DELEGATED（终态才 CANCELLED）。
                status = AssignmentStatus.DELEGATED;
                reason = null;
            }
        }
        ApproveRecord record =
                status == AssignmentStatus.COMPLETED ? approveRecord(vote) : null;
        return new TaskAssignment(
                participant,
                status,
                record,
                instant(assignment.assignedAtMs()),
                instant(assignment.resolvedAtMs()),
                reason);
    }

    private static AssignmentStatus assignmentStatus(String status, boolean hasVote) {
        if (status == null) {
            return AssignmentStatus.CANCELLED;
        }
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "pending", "created", "claimed" -> AssignmentStatus.PENDING;
            case "suspended" -> AssignmentStatus.SUSPENDED;
            case "delegated" -> AssignmentStatus.DELEGATED;
            case "transferred" -> AssignmentStatus.TRANSFERRED;
            case "completed", "rejected" ->
                    hasVote ? AssignmentStatus.COMPLETED : AssignmentStatus.CANCELLED;
            case "scheduled" -> AssignmentStatus.PENDING;
            case "cancelled", "expired" -> AssignmentStatus.CANCELLED;
            default -> AssignmentStatus.CANCELLED;
        };
    }

    private HumanTaskStatus status(
            ApprovalInstanceDetail detail, TaskGroup group, List<ApprovalTask> seats) {
        boolean withdrawn =
                seats.stream().anyMatch(seat -> "withdrawn".equalsIgnoreCase(seat.status()))
                        || withdrawInstanceRecorded(detail, group);
        if (withdrawn || "superseded".equalsIgnoreCase(group.status())) {
            return HumanTaskStatus.WITHDRAWN;
        }
        return switch (group.status().toLowerCase(java.util.Locale.ROOT)) {
            case "open", "suspended", "scheduled" -> HumanTaskStatus.PENDING;
            case "passed" -> HumanTaskStatus.COMPLETED;
            case "rejected" -> HumanTaskStatus.REJECTED;
            case "cancelled" -> HumanTaskStatus.CANCELLED;
            default -> HumanTaskStatus.CANCELLED;
        };
    }

    private boolean withdrawInstanceRecorded(
            ApprovalInstanceDetail detail, TaskGroup group) {
        if (detail == null) {
            return false;
        }
        for (Map<String, String> extras : sidecar.actions(group.instanceId()).values()) {
            if ("true".equals(extras.get("withdrawInstance"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean derived(ApprovalTask seat) {
        return seat != null
                && ((seat.spawnedByActionId() != null && !seat.spawnedByActionId().isBlank())
                        || (seat.predecessorTaskId() != null
                                && !seat.predecessorTaskId().isBlank()));
    }

    private static HumanTaskKind kind(ApprovalTask seat) {
        if (seat == null || seat.origin() == null || seat.origin().isBlank()) {
            return HumanTaskKind.ORIGINAL;
        }
        return switch (seat.origin().toLowerCase(java.util.Locale.ROOT)) {
            case "before_added" -> HumanTaskKind.BEFORE_ADDED;
            case "after_added" -> HumanTaskKind.AFTER_ADDED;
            default -> HumanTaskKind.ORIGINAL;
        };
    }

    private static ApproveRecord approveRecord(ApprovalVote vote) {
        if (vote == null) {
            return null;
        }
        Map<String, String> extra = new LinkedHashMap<>();
        if (vote.actingService() != null && !vote.actingService().isBlank()) {
            extra.put("actingService", vote.actingService());
        }
        return new ApproveRecord(
                vote.actor(),
                blankToNull(vote.onBehalfOf()),
                "reject".equalsIgnoreCase(vote.decision())
                        ? ActionDecision.REJECT
                        : ActionDecision.APPROVE,
                blankToNull(vote.comment()),
                instant(vote.atMs()),
                extra);
    }

    /** 实例钉死版本的 seedj 定义；coord 侧定义缺失时返回 null。 */
    WorkflowDefinition definitionOf(ApprovalInstanceDetail detail) {
        if (detail == null
                || detail.definitionNamespace() == null
                || detail.definitionNamespace().isBlank()) {
            return null;
        }
        try {
            WorkflowDefinitionId id =
                    definitions.definitionIdOf(
                            detail.definitionNamespace(), detail.definitionName());
            return definition(id, ids.seedjVersion(detail.definitionVersion())).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static cn.byteforce.seedj.workflow.spi.StepDefinition stepOf(
            WorkflowDefinition definition, String stepName) {
        if (definition == null || stepName == null) {
            return null;
        }
        return definition.steps().stream()
                .filter(step -> stepName.equals(step.stepName()))
                .findFirst()
                .orElse(null);
    }

    private Optional<WorkflowDefinition> definition(
            WorkflowDefinitionId id, WorkflowDefinitionVersion version) {
        String key =
                id.namespace() + "/" + id.name() + "@" + version.major() + "." + version.minor();
        return definitionCache.computeIfAbsent(key, ignored -> definitions.find(id, version));
    }

    private Map<String, String> metadata(ApprovalInstanceDetail detail, String stepName) {
        WorkflowDefinition definition = definitionOf(detail);
        var step = stepOf(definition, stepName);
        return step == null ? Map.of() : step.metadata();
    }

    // ──── 历史 ────

    List<WorkflowHistoryEvent> history(WorkflowInstanceId instanceId) {
        List<cn.byteforce.coord.sdk.workflow.ApprovalHistoryEvent> events;
        try {
            events = workflows.history(instanceId.value());
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new WorkflowNotFoundException(
                        "workflow instance not found: " + instanceId.value());
            }
            throw e;
        }
        ApprovalInstanceDetail detail = detailOrNull(instanceId.value());
        Map<String, String> seatRounds = new HashMap<>();
        if (detail != null) {
            for (ApprovalTask seat : detail.tasks()) {
                seatRounds.put(seat.id(), seat.roundId());
            }
        }
        List<WorkflowHistoryEvent> history = new ArrayList<>(events.size());
        for (var event : events) {
            clock.observe(event.occurredAtMs());
            String taskId = event.taskId();
            if (taskId != null && seatRounds.containsKey(taskId)) {
                taskId = seatRounds.get(taskId);
            }
            history.add(
                    new WorkflowHistoryEvent(
                            event.eventId(),
                            event.eventType(),
                            blankToNull(event.node()),
                            blankToNull(taskId),
                            blankToNull(event.actionId()),
                            instant(event.occurredAtMs()),
                            attributes(event.eventType(), event.attributesJson())));
        }
        // coord 0.4.5 的实例撤回在 target_node 非空时只提交撤回、不投影回退事件
        // （回退驱动会以「not suspended on an approval」失败，见 known-limitations）。
        // 适配器按 sidecar 记录的目标补齐 INSTANCE_ROLLED_BACK，保持 seedj 事件词汇。
        Map<String, String> withdrawExtras = null;
        String withdrawActionId = null;
        for (var entry : sidecar.actions(instanceId.value()).entrySet()) {
            if ("true".equals(entry.getValue().get("withdrawInstance"))) {
                withdrawExtras = entry.getValue();
                withdrawActionId = entry.getKey();
                break;
            }
        }
        boolean withdrawn =
                history.stream().anyMatch(e -> "INSTANCE_WITHDRAWN".equals(e.eventType()));
        Instant lastAt =
                history.isEmpty() ? Instant.now() : history.get(history.size() - 1).occurredAt();
        if (withdrawExtras != null && !withdrawn) {
            String operator = withdrawExtras.getOrDefault("operator", "");
            Map<String, String> taskAttributes = new TreeMap<>();
            taskAttributes.put("reason", "INSTANCE_WITHDRAWN");
            taskAttributes.put("operator", operator);
            history.add(
                    new WorkflowHistoryEvent(
                            instanceId.value() + ":instance:task-withdrawn",
                            "TASK_WITHDRAWN",
                            null,
                            null,
                            withdrawActionId,
                            lastAt,
                            taskAttributes));
            Map<String, String> instanceAttributes = new TreeMap<>();
            instanceAttributes.put("reason", "INSTANCE_WITHDRAWN");
            instanceAttributes.put("operator", operator);
            history.add(
                    new WorkflowHistoryEvent(
                            instanceId.value() + ":instance:withdrawn",
                            "INSTANCE_WITHDRAWN",
                            null,
                            null,
                            withdrawActionId,
                            lastAt,
                            instanceAttributes));
            withdrawn = true;
        }
        history = withdrawTaskFirst(history);
        history = withdrawnFirst(history);
        boolean rolledBack =
                history.stream().anyMatch(e -> "INSTANCE_ROLLED_BACK".equals(e.eventType()));
        if (withdrawn && !rolledBack && withdrawExtras != null) {
            Map<String, String> synthetic = new TreeMap<>();
            synthetic.put("to_node", withdrawExtras.get("targetStep"));
            synthetic.put("by", withdrawExtras.getOrDefault("operator", ""));
            history.add(
                    new WorkflowHistoryEvent(
                            instanceId.value() + ":instance:rolled-back",
                            "INSTANCE_ROLLED_BACK",
                            withdrawExtras.get("targetStep"),
                            null,
                            withdrawActionId,
                            lastAt,
                            synthetic));
        }
        return history;
    }

    private static final java.util.Set<String> WITHDRAW_EFFECTS =
            java.util.Set.of(
                    "TASK_WITHDRAWN",
                    "TASK_ASSIGNMENT_AUTO_CANCELLED",
                    "TASK_CANCELLED",
                    "TASK_CREATED",
                    "ROUND_SUPERSEDED",
                    "ROUND_CANCELLED",
                    "ROUND_OPENED",
                    "ROUND_SUSPENDED",
                    "INSTANCE_ROLLED_BACK",
                    "TASK_AFTER_SPAWN_DISCARDED");

    /**
     * 任务级撤回：coord 把回退的副作用（席位自动取消、轮次取消/重开、丢弃后加签）与
     * 审计投影的 TASK_WITHDRAWN 放在同一时间片但顺序不同，seedj 契约要求
     * TASK_WITHDRAWN → 取消 → INSTANCE_ROLLED_BACK。对同一时间片的回退事件按 seedj
     * 词汇稳定重排，事件内容不变。
     */
    private static List<WorkflowHistoryEvent> withdrawTaskFirst(
            List<WorkflowHistoryEvent> history) {
        int withdrawn = -1;
        for (int i = 0; i < history.size(); i++) {
            WorkflowHistoryEvent event = history.get(i);
            if ("TASK_WITHDRAWN".equals(event.eventType()) && event.taskId() != null) {
                withdrawn = i;
                break;
            }
        }
        if (withdrawn < 0) {
            return history;
        }
        Instant at = history.get(withdrawn).occurredAt();
        int start = withdrawn;
        int end = withdrawn;
        while (start > 0 && withdrawWindow(history.get(start - 1), at)) {
            start--;
        }
        while (end + 1 < history.size() && withdrawWindow(history.get(end + 1), at)) {
            end++;
        }
        if (start == end) {
            return history;
        }
        List<WorkflowHistoryEvent> ordered = new ArrayList<>(history.subList(0, start));
        List<WorkflowHistoryEvent> window = new ArrayList<>(history.subList(start, end + 1));
        window.sort(
                java.util.Comparator.comparingInt(
                        event -> withdrawRank(event.eventType())));
        ordered.addAll(window);
        ordered.addAll(history.subList(end + 1, history.size()));
        return ordered;
    }

    private static boolean withdrawWindow(WorkflowHistoryEvent event, Instant at) {
        if (!WITHDRAW_EFFECTS.contains(event.eventType())) {
            return false;
        }
        return Math.abs(java.time.Duration.between(event.occurredAt(), at).toMillis()) <= 2;
    }

    private static int withdrawRank(String eventType) {
        return switch (eventType) {
            case "TASK_WITHDRAWN" -> 0;
            case "TASK_ASSIGNMENT_AUTO_CANCELLED" -> 1;
            case "TASK_CANCELLED" -> 2;
            case "TASK_CREATED" -> 3;
            case "ROUND_SUPERSEDED", "ROUND_CANCELLED", "ROUND_OPENED", "ROUND_SUSPENDED" -> 4;
            case "INSTANCE_ROLLED_BACK" -> 5;
            default -> 6;
        };
    }

    /** coord 的投影可能先出 INSTANCE_ROLLED_BACK 再出 TASK_WITHDRAWN；seedj 事件序要求反过来。 */
    private static List<WorkflowHistoryEvent> withdrawnFirst(List<WorkflowHistoryEvent> history) {
        int rollback = -1;
        int withdrawn = -1;
        for (int i = 0; i < history.size(); i++) {
            String type = history.get(i).eventType();
            if (rollback < 0 && "INSTANCE_ROLLED_BACK".equals(type)) {
                rollback = i;
            }
            if (withdrawn < 0 && "TASK_WITHDRAWN".equals(type)) {
                withdrawn = i;
            }
        }
        if (rollback < 0 || withdrawn < 0 || withdrawn < rollback) {
            return history;
        }
        List<WorkflowHistoryEvent> ordered = new ArrayList<>(history);
        WorkflowHistoryEvent event = ordered.remove(withdrawn);
        ordered.add(rollback, event);
        return ordered;
    }

    private Map<String, String> attributes(String eventType, String json) {
        Map<String, String> attributes = new TreeMap<>();
        if (json == null || json.isBlank()) {
            return attributes;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (RuntimeException e) {
            return attributes;
        }
        if (!node.isObject()) {
            return attributes;
        }
        for (var field : node.properties()) {
            String key = field.getKey();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode item : value) {
                    if (joined.length() > 0) {
                        joined.append(',');
                    }
                    joined.append(item.isString() ? item.stringValue() : item.toString());
                }
                attributes.put(key, joined.toString());
            } else {
                attributes.put(key, value.isString() ? value.stringValue() : value.toString());
            }
        }
        if ("TASK_REJECTED".equals(eventType)) {
            uppercase(attributes, "reason");
            if ("ON_EXHAUSTED".equals(attributes.get("reason"))) {
                attributes.put("reason", "SIGN_COMPLETION_UNSATISFIABLE");
            }
        }
        if ("TASK_AFTER_SPAWN_DISCARDED".equals(eventType)) {
            uppercase(attributes, "reason");
        }
        if ("TASK_URGED".equals(eventType)) {
            uppercase(attributes, "targetStatus");
        }
        return attributes;
    }

    private static void uppercase(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value != null && !value.isBlank()) {
            attributes.put(key, value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    // ──── 共享辅助（动作层也用） ────

    ApprovalInstanceDetail detailOrNull(String instanceId) {
        try {
            ApprovalInstanceDetail detail = workflows.getInstanceDetail(instanceId);
            if (detail != null) {
                clock.observe(Instant.now());
            }
            return detail;
        } catch (CoordException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    static ApprovalRound findRound(ApprovalInstanceDetail detail, String roundId) {
        if (detail == null) {
            return null;
        }
        return detail.rounds().stream()
                .filter(round -> round.id().equals(roundId))
                .findFirst()
                .orElse(null);
    }

    static List<ApprovalTask> roundSeats(ApprovalInstanceDetail detail, String roundId) {
        return detail.tasks().stream().filter(task -> task.roundId().equals(roundId)).toList();
    }

    static String instanceIdOfSeat(ApprovalInstanceDetail detail, String seatId) {
        return detail.tasks().stream()
                .filter(task -> task.id().equals(seatId))
                .map(ApprovalTask::instanceId)
                .findFirst()
                .orElse(detail.instanceId());
    }

    // ──── 状态/字段映射 ────

    static WorkflowStatus status(WorkflowState state, String approvalStatus) {
        return status(state, approvalStatus, null);
    }

    /** 显式终止与「审批聚合因超时/驳回收口」共用 approval_status=TERMINATED，用 fault 区分。 */
    static WorkflowStatus status(
            WorkflowState state, String approvalStatus, WorkflowFault fault) {
        if (state == null) {
            return WorkflowStatus.PENDING;
        }
        return switch (state) {
            case PENDING -> WorkflowStatus.PENDING;
            case RUNNING -> WorkflowStatus.RUNNING;
            case WAITING -> WorkflowStatus.WAITING;
            case SUSPENDED -> WorkflowStatus.RUNNING;
            case COMPLETED, COMPENSATED -> WorkflowStatus.COMPLETED;
            case FAILED, FAULTED, TIMED_OUT ->
                    terminated(approvalStatus, fault)
                            ? WorkflowStatus.TERMINATED
                            : WorkflowStatus.FAILED;
            case CANCELLED -> WorkflowStatus.CANCELLED;
        };
    }

    private static boolean terminated(String approvalStatus, WorkflowFault fault) {
        if (!"TERMINATED".equalsIgnoreCase(approvalStatus)) {
            return false;
        }
        if (fault == null) {
            return true;
        }
        String type = fault.type() == null ? "" : fault.type().toLowerCase(java.util.Locale.ROOT);
        String title =
                fault.title() == null ? "" : fault.title().toLowerCase(java.util.Locale.ROOT);
        return type.contains("terminated") || title.contains("terminated");
    }

    static WorkflowStatus status(String coordState, String approvalStatus) {
        return status(WorkflowState.fromProtoName(coordState), approvalStatus);
    }

    private static boolean isTerminal(WorkflowStatus status) {
        return status == WorkflowStatus.COMPLETED
                || status == WorkflowStatus.FAILED
                || status == WorkflowStatus.CANCELLED
                || status == WorkflowStatus.TERMINATED;
    }

    private static boolean isTerminal(String coordState) {
        return WorkflowState.fromProtoName(coordState).isTerminal();
    }

    private static boolean matchesSeedjStatus(WorkflowInstance instance, WorkflowStatus wanted) {
        return wanted == null || instance.status() == wanted;
    }

    private static List<String> coordStatuses(WorkflowStatus status) {
        if (status == null) {
            return List.of();
        }
        return switch (status) {
            case RUNNING -> List.of("RUNNING", "SUSPENDED");
            case FAILED -> List.of("FAULTED", "FAILED", "TIMED_OUT");
            case TERMINATED -> List.of("FAULTED", "FAILED");
            case COMPLETED -> List.of("COMPLETED", "COMPENSATED");
            case CANCELLED -> List.of("CANCELLED");
            case PENDING -> List.of("PENDING");
            case WAITING -> List.of("WAITING");
            case COMPENSATING -> List.of("COMPENSATING");
        };
    }

    private static WorkflowFailure failure(
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status, String currentStep) {
        WorkflowFault fault = status.fault();
        if (fault == null) {
            if (status.state() == WorkflowState.FAILED
                    || status.state() == WorkflowState.FAULTED) {
                return new WorkflowFailure(
                        "WORKFLOW_FAILED",
                        status.errorMessage() == null ? "workflow failed" : status.errorMessage(),
                        false,
                        currentStep,
                        Map.of());
            }
            return null;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("type", fault.type());
        details.put("status", Integer.toString(fault.status()));
        details.put("instance", fault.instance());
        return new WorkflowFailure(
                blankToNull(fault.title()) == null ? fault.type() : fault.title(),
                blankToNull(fault.detail()) == null ? fault.title() : fault.detail(),
                false,
                currentStep,
                details);
    }

    private static Map<String, String> variables(String json) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return variables;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return variables;
            }
            JsonNode signal = node.get("_signal");
            if (signal != null && signal.isObject()) {
                JsonNode payload = signal.get("payload");
                if (payload != null && payload.isObject()) {
                    for (var field : payload.properties()) {
                        JsonNode value = field.getValue();
                        if (value != null && !value.isNull()) {
                            variables.put(
                                    field.getKey(),
                                    value.isString() ? value.stringValue() : value.toString());
                        }
                    }
                }
            }
            for (var field : node.properties()) {
                if (field.getKey().startsWith("_")) {
                    continue;
                }
                JsonNode value = field.getValue();
                if (value == null || value.isNull()) {
                    continue;
                }
                variables.put(
                        field.getKey(),
                        value.isString() ? value.stringValue() : value.toString());
            }
        } catch (RuntimeException e) {
            // 非 JSON input：不暴露原始字节，返回空变量集。
        }
        return variables;
    }

    static int cursorOffset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant minInstant(java.util.stream.LongStream values) {
        long value = values.filter(item -> item > 0).min().orElse(0);
        return instant(value);
    }

    private static Instant maxInstant(java.util.stream.LongStream values) {
        long value = values.filter(item -> item > 0).max().orElse(0);
        return instant(value);
    }

    private static Instant instant(long epochMillis) {
        return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 一次读面快照：GetStatus + 审批详情（无审批聚合时为 {@code null}）。 */
    record Snapshot(
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status, ApprovalInstanceDetail detail) {}
}
