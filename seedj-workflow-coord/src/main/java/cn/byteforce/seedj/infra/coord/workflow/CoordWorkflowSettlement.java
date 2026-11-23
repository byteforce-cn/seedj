package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.workflow.WorkflowClient;
import cn.byteforce.seedj.workflow.spi.AddAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.RemoveAssigneeAction;
import cn.byteforce.seedj.workflow.spi.ResumeWorkflowAction;
import cn.byteforce.seedj.workflow.spi.SignalAction;
import cn.byteforce.seedj.workflow.spi.TransferAction;
import cn.byteforce.seedj.workflow.spi.DelegateAction;
import cn.byteforce.seedj.workflow.spi.WithdrawAction;
import cn.byteforce.seedj.workflow.spi.WithdrawInstanceAction;
import cn.byteforce.seedj.workflow.spi.WorkflowAction;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;

/**
 * 动作返回前的落定判定：coord 的命令同步提交，但工作流推进由后台驱动完成，且
 * {@code GetStatus} 是 runtime 的本地缓存投影、{@code GetInstanceDetail} 才是权威读面。
 * 这里把三个节奏收敛成一次可读快照，避免把陈旧读面当成动作结果返回。
 */
final class CoordWorkflowSettlement {

    private final WorkflowClient workflows;
    private final CoordWorkflowReads reads;

    CoordWorkflowSettlement(WorkflowClient workflows, CoordWorkflowReads reads) {
        this.workflows = workflows;
        this.reads = reads;
    }

    /**
     * coord 的命令同步提交，但工作流推进由后台驱动完成；{@code GetStatus} 还有 2–3s 的本地
     * 缓存投影，{@code GetInstanceDetail} 才是权威读面。动作返回前等到「detail 的 open 分支
     * 节点」与「状态报告的 currentStateName」一致，或轮次仍开放（票已落库、无需等驱动）。
     */
    void await(WorkflowInstanceId instanceId, WorkflowAction action) {
        long capMillis =
                switch (action) {
                    case null -> 10_000;
                    case ApproveAction ignored -> 10_000;
                    case RejectAction ignored -> 10_000;
                    case WithdrawAction ignored -> 10_000;
                    case WithdrawInstanceAction ignored -> 10_000;
                    case ResumeWorkflowAction ignored -> 10_000;
                    case SignalAction ignored -> 10_000;
                    case AddAssigneeAction ignored -> 5_000;
                    default -> 2_000;
                };
        long deadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(capMillis);
        String targetRound = targetRound(action);
        int stable = 0;
        while (System.nanoTime() < deadline) {
            try {
                var status = workflows.getStatus(instanceId.value());
                if (status.state().isTerminal()) {
                    return;
                }
                var detail = reads.detailOrNull(instanceId.value());
                if (System.getProperty("workflow.debug") != null) {
                    System.out.println(
                            "DEBUG settle action="
                                    + action
                                    + " state="
                                    + status.state()
                                    + " current="
                                    + status.currentStateName()
                                    + " branches="
                                    + (detail == null
                                            ? "-"
                                            : detail.branches().stream()
                                                    .map(b -> b.node() + ":" + b.status())
                                                    .toList())
                                    + " rounds="
                                    + (detail == null
                                            ? "-"
                                            : detail.rounds().stream()
                                                    .map(r -> r.id() + ":" + r.status())
                                                    .toList())
                                    + " targetOpen="
                                    + roundOpen(detail, targetRound));
                }
                if (settled(action, status, detail, targetRound)) {
                    if (++stable >= 2) {
                        return;
                    }
                } else {
                    stable = 0;
                }
            } catch (CoordException e) {
                if (System.getProperty("workflow.debug") != null) {
                    System.out.println(
                            "DEBUG settle abort action="
                                    + action
                                    + " code="
                                    + e.getErrorCode()
                                    + " msg="
                                    + e.getMessage());
                }
                return;
            }
            sleep(50);
        }
        if (System.getProperty("workflow.debug") != null) {
            System.out.println("DEBUG settle timeout action=" + action);
        }
    }

    private static String targetRound(WorkflowAction action) {
        return switch (action) {
            case ApproveAction approve -> approve.taskId().value();
            case RejectAction reject -> reject.taskId().value();
            case TransferAction transfer -> transfer.taskId().value();
            case DelegateAction delegate -> delegate.taskId().value();
            case WithdrawAction withdraw -> withdraw.taskId().value();
            case AddAssigneeAction add -> add.taskId().value();
            case RemoveAssigneeAction remove -> remove.taskId().value();
            case null, default -> null;
        };
    }

    private static boolean settled(
            WorkflowAction action,
            cn.byteforce.coord.sdk.workflow.WorkflowStatus status,
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail,
            String targetRound) {
        boolean consistent = openNode(detail) == null || openNode(detail).equals(status.currentStateName());
        if (action == null || action instanceof WithdrawInstanceAction) {
            // cancel / terminate / 实例撤回：只在终态收口。
            return false;
        }
        if (action instanceof RejectAction || action instanceof WithdrawAction) {
            // 回退驱动：等实例真正落在重开的审批节点上，而不是回退途中的活动节点。
            return openNode(detail) != null && consistent;
        }
        if (action instanceof SignalAction) {
            // 信号恢复 wait/listen：等驱动推进到终态或下一个审批挂起，不能停在 WAITING。
            if (status.state()
                    == cn.byteforce.coord.sdk.workflow.WorkflowState.WAITING) {
                return false;
            }
            if (status.state()
                    == cn.byteforce.coord.sdk.workflow.WorkflowState.SUSPENDED) {
                String liveNode = liveRoundNode(detail);
                return liveNode != null && liveNode.equals(status.currentStateName());
            }
            return false;
        }
        if (action instanceof ApproveAction || action instanceof ResumeWorkflowAction) {
            if (roundOpen(detail, targetRound)) {
                // 票/续期已同步落库，节点仍在收集：不需要等驱动。读面可能落后于命令提交，
                // 所以「轮次仍 open」要带上已计票的证据，否则会把收口前的旧轮次当成已落库。
                return action instanceof ResumeWorkflowAction
                        || roundCounted(detail, targetRound);
            }
            // 目标轮次已收口：必须等驱动推进到下一个节点。分支/轮次在「轮次已通过、
            // 驱动尚未推进」的窗口里仍是旧节点，只看 open 分支会过早返回。
            String targetNode = roundNode(detail, targetRound);
            String liveNode = liveRoundNode(detail);
            if (liveNode != null && !liveNode.equals(targetNode)) {
                return liveNode.equals(status.currentStateName());
            }
            // 下一步不是审批（活动/完成）：等终态或 runtime 进入 WAITING（wait/listen）。
            // GetStatus 是 runtime 的缓存投影：本轮收口后可能仍读到**收口前**的 WAITING。
            // 用 updatedAt 与轮次 closedAt 比较，确认读面已越过本轮收口再认这个 WAITING，
            // 否则会把陈旧的等待态当成「已推进到 listen」，动作返回错误快照。
            if (status.state() != cn.byteforce.coord.sdk.workflow.WorkflowState.WAITING) {
                return false;
            }
            long closedAt = roundClosedAt(detail, targetRound);
            return closedAt <= 0 || status.updatedAt() >= closedAt;
        }
        // 加/减席位、催办、抄送、转办、委托、信号：新轮次出现后等缓存状态追上；
        // 没有状态机变化时两拍即返回。
        return consistent;
    }

    private static boolean roundOpen(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return false;
        }
        for (var round : detail.rounds()) {
            if (round.id().equals(roundId)) {
                String value = round.status() == null ? "" : round.status().toLowerCase();
                return value.equals("open") || value.equals("suspended");
            }
        }
        return false;
    }

    /** 轮次里已有席位收口（通过或否决）：读面已看到命令效果的证据。 */
    private static boolean roundCounted(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return false;
        }
        return detail.rounds().stream()
                .filter(round -> round.id().equals(roundId))
                .anyMatch(round -> round.completedCount() > 0 || round.rejectedCount() > 0);
    }

    /** 轮次收口时间（ms）；轮次不存在或未收口返回 0。 */
    private static long roundClosedAt(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return 0;
        }
        return detail.rounds().stream()
                .filter(round -> round.id().equals(roundId))
                .mapToLong(cn.byteforce.coord.sdk.workflow.ApprovalRound::closedAtMs)
                .findFirst()
                .orElse(0);
    }

    private static String openNode(cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail) {
        if (detail == null) {
            return null;
        }
        for (var branch : detail.branches()) {
            if ("open".equalsIgnoreCase(branch.status())) {
                return branch.node();
            }
        }
        return null;
    }

    /** 仍可收集票的轮次所在节点（open/suspended 且分支 open）；没有则 {@code null}。 */
    private static String liveRoundNode(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail) {
        if (detail == null) {
            return null;
        }
        var round =
                detail.rounds().stream()
                        .filter(
                                candidate ->
                                        "open".equalsIgnoreCase(candidate.status())
                                                || "suspended"
                                                        .equalsIgnoreCase(candidate.status()))
                        .filter(
                                candidate ->
                                        detail.branches().stream()
                                                .anyMatch(
                                                        branch ->
                                                                branch.id()
                                                                                .equals(
                                                                                        candidate
                                                                                                .branchId())
                                                                        && "open"
                                                                                .equalsIgnoreCase(
                                                                                        branch
                                                                                                .status())))
                        .max(java.util.Comparator.comparingLong(
                                cn.byteforce.coord.sdk.workflow.ApprovalRound::openedAtMs))
                        .orElse(null);
        if (round == null) {
            return null;
        }
        String branchId = round.branchId();
        return detail.branches().stream()
                .filter(branch -> branch.id().equals(branchId))
                .map(cn.byteforce.coord.sdk.workflow.ApprovalBranch::node)
                .findFirst()
                .orElse(null);
    }

    private static String roundNode(
            cn.byteforce.coord.sdk.workflow.ApprovalInstanceDetail detail, String roundId) {
        if (detail == null || roundId == null) {
            return null;
        }
        for (var round : detail.rounds()) {
            if (round.id().equals(roundId)) {
                String branchId = round.branchId();
                return detail.branches().stream()
                        .filter(branch -> branch.id().equals(branchId))
                        .map(cn.byteforce.coord.sdk.workflow.ApprovalBranch::node)
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
