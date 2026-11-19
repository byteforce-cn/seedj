package cn.byteforce.seedj.starter.ai.approval;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.ai.ApprovalStatus;
import cn.byteforce.seedj.ai.DecisionEffect;
import cn.byteforce.seedj.ai.DecisionIntent;
import cn.byteforce.seedj.ai.DecisionRecord;
import cn.byteforce.seedj.ai.DecisionRecordKind;
import cn.byteforce.seedj.ai.DecisionTokenClaims;
import cn.byteforce.seedj.ai.OutcomeSource;
import cn.byteforce.seedj.ai.spi.DecisionLedger;
import cn.byteforce.seedj.core.clock.Clock;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.starter.ai.AiProperties;
import cn.byteforce.seedj.starter.ai.ledger.RecordedApproval;
import cn.byteforce.seedj.starter.ai.ledger.RecordedIntent;
import cn.byteforce.seedj.starter.ai.token.DecisionTokenIssuer;
import cn.byteforce.seedj.workflow.spi.ActionRequest;
import cn.byteforce.seedj.workflow.spi.ApproveAction;
import cn.byteforce.seedj.workflow.spi.CancelWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.RejectAction;
import cn.byteforce.seedj.workflow.spi.StartWorkflowRequest;
import cn.byteforce.seedj.workflow.spi.TaskId;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinition;
import cn.byteforce.seedj.workflow.spi.WorkflowInstance;
import cn.byteforce.seedj.workflow.spi.WorkflowInstanceId;
import cn.byteforce.seedj.workflow.spi.WorkflowProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * 审批服务：把不可逆动作的意图变成一条可执行的前置条件。
 *
 * <p><b>顺序是契约</b>：intent 先落账（由 {@code DecisionService} 保证），再启动工作流并追加 {@code
 * APPROVAL(PENDING)}；批准/驳回/过期都只追加新记录，绝不改写旧记录。grant 每次重新读 intent 与最新 approval，
 * 只有「approved + argsHash 一致 + 未过期」才签 token。
 *
 * <p>工作流只是审批状态的来源之一：账本里的 {@code APPROVAL} 记录才是事实。工作流实例被删、Provider 不可用时， grant
 * 只看账本；过期扫描尽力取消工作流，取消失败只 WARN。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ApprovalService {

    /** 审批驳回后的 outcome error_code。 */
    public static final String REJECTED_BY_APPROVAL = "APPROVAL_REJECTED";

    /** 审批超时后的 outcome error_code。 */
    public static final String APPROVAL_EXPIRED = "APPROVAL_EXPIRED";

    /** 审批未装配/启动失败时的 deny rule。 */
    public static final String APPROVAL_UNAVAILABLE = "APPROVAL_UNAVAILABLE";

    /** 审批启动失败时的 outcome error_code。 */
    public static final String APPROVAL_START_FAILED = "APPROVAL_START_FAILED";

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WorkflowProvider workflows;

    private final DecisionLedger ledger;

    private final DecisionTokenIssuer issuer;

    private final Clock clock;

    private final ApprovalProperties properties;

    private final AiProperties ai;

    private final ApprovalNotifier notifier;

    private final UUID tenantId;

    private final UUID onBehalfOf;

    private final AtomicBoolean expiryWarned = new AtomicBoolean();

    /**
     * 绑定审批依赖。
     *
     * @param workflows 工作流端口
     * @param ledger 决策账本
     * @param issuer token 签发方
     * @param clock 取时入口
     * @param properties 审批配置
     * @param ai AI 配置（租户、身份、token TTL）
     * @param notifier 通知端口
     * @throws IllegalStateException 租户/身份/审批人配置缺失或非法
     */
    public ApprovalService(
            WorkflowProvider workflows,
            DecisionLedger ledger,
            DecisionTokenIssuer issuer,
            Clock clock,
            ApprovalProperties properties,
            AiProperties ai,
            ApprovalNotifier notifier) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        if (properties.approverIds().isEmpty()) {
            throw new IllegalStateException("seedj.ai.approval.approvers 至少一个");
        }
        this.tenantId = requiredTenant(ai.plane().tenant());
        this.onBehalfOf = requiredUuid(ai.identity().onBehalfOf());
    }

    /**
     * 注册审批定义；已存在同 id/version 时不覆盖。
     *
     * @throws cn.byteforce.seedj.workflow.spi.WorkflowException Provider 注册失败
     */
    public void registerDefinition() {
        WorkflowDefinition definition =
                ApprovalWorkflowDefinition.definition(properties, properties.approverIds());
        if (workflows.findDefinition(definition.id(), definition.version()).isEmpty()) {
            workflows.register(definition);
        }
    }

    /**
     * 为一条已落账的意图启动审批。
     *
     * @param intent 决策意图
     * @param audience token 受众
     * @return 已落账的 PENDING 快照
     * @throws ContractException {@code unavailable}：工作流启动失败（intent 已落账，已追加 UNKNOWN +
     *     outcome(rejected)）
     */
    public ApprovalSnapshot start(DecisionIntent intent, String audience) {
        Objects.requireNonNull(intent, "intent");
        WorkflowInstanceId instanceId =
                new WorkflowInstanceId("agent-approval-" + intent.decisionId());
        Instant now = clock.now();
        Instant deadline = now.plus(properties.deadline());
        try {
            WorkflowInstance instance =
                    workflows.start(
                            new StartWorkflowRequest(
                                    instanceId,
                                    properties.definitionId(),
                                    properties.definitionVersion(),
                                    intent.identity().actor().toString(),
                                    variables(intent, audience, now, deadline)));
            if (instance.pendingTaskIds().isEmpty()) {
                throw new IllegalStateException("审批实例没有生成 pending task");
            }
            ApprovalSnapshot pending =
                    new ApprovalSnapshot(
                            instanceId.value(),
                            instance.pendingTaskIds().get(0).value(),
                            ApprovalStatus.PENDING,
                            now,
                            null,
                            null,
                            intent.argsHash(),
                            null);
            ledger.appendApproval(intent.decisionId(), pending);
            notifyPending(intent, pending, deadline);
            return pending;
        } catch (RuntimeException error) {
            ApprovalSnapshot unknown =
                    new ApprovalSnapshot(
                            instanceId.value(),
                            "unavailable",
                            ApprovalStatus.UNKNOWN,
                            now,
                            null,
                            null,
                            intent.argsHash(),
                            null);
            ledger.appendApproval(intent.decisionId(), unknown);
            ledger.appendOutcome(
                    intent.decisionId(),
                    DecisionEffect.rejected(APPROVAL_START_FAILED),
                    OutcomeSource.PLANE_DECIDED);
            LOG.warn("审批启动失败（intent 已落账）: {}", intent.decisionId(), error);
            throw new ContractException(
                    ContractErrorCode.UNAVAILABLE,
                    "审批启动失败（intent 已落账，decision_id=" + intent.decisionId() + "）");
        }
    }

    /**
     * 读取一个决策的审批视图。
     *
     * @param decisionId 决策标识
     * @return 审批视图
     * @throws ContractException {@code object_not_found}：没有审批记录
     */
    public ApprovalView view(UUID decisionId) {
        ApprovalSnapshot snapshot = latest(decisionId);
        return new ApprovalView(
                decisionId,
                snapshot.status().wire(),
                snapshot.instanceId(),
                snapshot.taskId(),
                snapshot.requestedAt(),
                deadlineOf(snapshot),
                snapshot.approver(),
                snapshot.approvedAt(),
                snapshot.argsHash(),
                properties.approverIds());
    }

    /**
     * 对一条待审批决策执行批准/驳回。
     *
     * @param decisionId 决策标识
     * @param action 动作
     * @param approver 审批人
     * @param comment 意见；可空
     * @return 新的审批快照
     * @throws ContractException {@code forbidden}：非审批人或自审批；{@code version_conflict}：已终结
     */
    public ApprovalSnapshot act(
            UUID decisionId, ApprovalAction action, UUID approver, String comment) {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(approver, "approver");
        ApprovalSnapshot current = latest(decisionId);
        if (current.status() == ApprovalStatus.APPROVED
                && approver.equals(current.approver())
                && action == ApprovalAction.APPROVE) {
            return current;
        }
        if (current.status() != ApprovalStatus.PENDING) {
            throw new ContractException(
                    ContractErrorCode.VERSION_CONFLICT,
                    "审批已终结: " + current.status().wire());
        }
        if (!properties.approverIds().contains(approver)) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN, "审批人不在 seedj.ai.approval.approvers 内");
        }
        if (!properties.allowSelfApprove() && approver.equals(onBehalfOf)) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN, "不允许发起人自审批（seedj.ai.approval.allow-self-approve=false）");
        }
        Instant now = clock.now();
        String actionId = "approval:" + decisionId + ":" + approver + ":" + action.wire();
        if (action == ApprovalAction.APPROVE) {
            workflows.act(
                    new ActionRequest(
                            new WorkflowInstanceId(current.instanceId()),
                            new ApproveAction(
                                    actionId,
                                    new TaskId(current.taskId()),
                                    approver.toString(),
                                    comment,
                                    Map.of(),
                                    now)));
        } else {
            workflows.act(
                    new ActionRequest(
                            new WorkflowInstanceId(current.instanceId()),
                            new RejectAction(
                                    actionId,
                                    new TaskId(current.taskId()),
                                    approver.toString(),
                                    "close",
                                    comment,
                                    now)));
        }
        ApprovalSnapshot next =
                new ApprovalSnapshot(
                        current.instanceId(),
                        current.taskId(),
                        action == ApprovalAction.APPROVE
                                ? ApprovalStatus.APPROVED
                                : ApprovalStatus.REJECTED,
                        current.requestedAt(),
                        approver,
                        action == ApprovalAction.APPROVE ? now : null,
                        current.argsHash(),
                        null);
        ledger.appendApproval(decisionId, next);
        if (action == ApprovalAction.REJECT) {
            ledger.appendOutcome(
                    decisionId,
                    DecisionEffect.rejected(REJECTED_BY_APPROVAL),
                    OutcomeSource.PLANE_DECIDED);
        }
        notifyResolved(decisionId, current, next);
        return next;
    }

    /**
     * 为一条已批准的决策签发 token。
     *
     * @param decisionId 决策标识
     * @return JWS compact 串
     * @throws ContractException {@code version_conflict}：未批准/已过期；{@code object_not_found}：没有意图
     */
    public String grant(UUID decisionId) {
        Objects.requireNonNull(decisionId, "decisionId");
        RecordedIntent intent = recordedIntent(decisionId);
        ApprovalSnapshot approval = latest(decisionId);
        if (approval.status() != ApprovalStatus.APPROVED) {
            throw new ContractException(
                    ContractErrorCode.VERSION_CONFLICT,
                    "审批未通过: " + approval.status().wire());
        }
        Instant now = clock.now();
        Instant deadline = deadlineOf(approval);
        if (!now.isBefore(deadline)) {
            expireOne(decisionId, now);
            throw new ContractException(ContractErrorCode.VERSION_CONFLICT, "审批已过期");
        }
        if (!approval.argsHash().equals(intent.argsHash())) {
            throw new IllegalStateException(
                    "approval args_hash 与 intent 不一致: " + decisionId);
        }
        Duration ttl = ai.decision().ttl();
        Duration remaining = Duration.between(now, deadline);
        if (remaining.compareTo(ttl) < 0) {
            ttl = remaining;
        }
        String audience = audienceOf(approval.instanceId());
        DecisionTokenClaims claims =
                DecisionTokenFactory.claims(intent, approval, now, now.plus(ttl), audience);
        return issuer.sign(claims);
    }

    /**
     * 让一条已过期的 PENDING 审批终结。
     *
     * @param decisionId 决策标识
     * @param now 当前时间
     * @return 追加了 EXPIRED 返回 1；不是 PENDING 或未到期返回 0
     */
    public int expireOne(UUID decisionId, Instant now) {
        ApprovalSnapshot current = latest(decisionId);
        if (current.status() != ApprovalStatus.PENDING
                || now.isBefore(deadlineOf(current))) {
            return 0;
        }
        ApprovalSnapshot expired =
                new ApprovalSnapshot(
                        current.instanceId(),
                        current.taskId(),
                        ApprovalStatus.EXPIRED,
                        current.requestedAt(),
                        null,
                        null,
                        current.argsHash(),
                        null);
        ledger.appendApproval(decisionId, expired);
        ledger.appendOutcome(
                decisionId, DecisionEffect.rejected(APPROVAL_EXPIRED), OutcomeSource.PLANE_DECIDED);
        cancelQuietly(current.instanceId());
        notifyResolved(decisionId, current, expired);
        return 1;
    }

    /**
     * 扫描并终结过期审批；全量扫描依赖账本的 {@code pendingApprovals}（T3 的 Task B6 接入）。
     *
     * @param now 当前时间
     * @return 本次终结条数
     */
    public int expirePending(Instant now) {
        Objects.requireNonNull(now, "now");
        if (expiryWarned.compareAndSet(false, true)) {
            LOG.warn(
                    "approval expiry sweep is a no-op until ledger pendingApprovals is wired"
                            + " (T3 Task B6); expiring on read/grant still works");
        }
        return 0;
    }

    /**
     * 审批视图的截止时间。
     *
     * @param snapshot 审批快照
     * @return {@code requestedAt + deadline}
     */
    public Instant deadlineOf(ApprovalSnapshot snapshot) {
        return snapshot.requestedAt().plus(properties.deadline());
    }

    private ApprovalSnapshot latest(UUID decisionId) {
        List<DecisionRecord> records = ledger.byDecision(tenantId, decisionId);
        return records.stream()
                .filter(record -> record.kind() == DecisionRecordKind.APPROVAL)
                .reduce((first, second) -> second)
                .map(RecordedApproval::of)
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.OBJECT_NOT_FOUND,
                                        "决策没有审批记录: " + decisionId));
    }

    private RecordedIntent recordedIntent(UUID decisionId) {
        return ledger.byDecision(tenantId, decisionId).stream()
                .filter(record -> record.kind() == DecisionRecordKind.INTENT)
                .findFirst()
                .map(RecordedIntent::of)
                .orElseThrow(
                        () ->
                                new ContractException(
                                        ContractErrorCode.OBJECT_NOT_FOUND,
                                        "决策不存在: " + decisionId));
    }

    private String audienceOf(String instanceId) {
        String fromWorkflow = null;
        try {
            Optional<WorkflowInstance> instance =
                    workflows.describe(new WorkflowInstanceId(instanceId));
            if (instance.isPresent()) {
                fromWorkflow = instance.get().variables().get("audience");
            }
        } catch (RuntimeException error) {
            LOG.warn("审批实例不可读，回退 seedj.ai.decision.audience: {}", instanceId, error);
        }
        String configured = ai.decision().audience();
        String resolved =
                fromWorkflow == null || fromWorkflow.isBlank() ? configured : fromWorkflow;
        if (resolved == null || resolved.isBlank()) {
            throw new ContractException(
                    ContractErrorCode.UNAVAILABLE, "缺少 audience：审批实例未记录且 seedj.ai.decision.audience 未配置");
        }
        return resolved;
    }

    private void cancelQuietly(String instanceId) {
        try {
            workflows.cancel(
                    new CancelWorkflowRequest(
                            new WorkflowInstanceId(instanceId), "approval deadline", false));
        } catch (RuntimeException error) {
            LOG.warn("审批过期后取消工作流失败（账本结论不受影响）: {}", instanceId, error);
        }
    }

    private Map<String, String> variables(
            DecisionIntent intent, String audience, Instant now, Instant deadline) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("decision_id", intent.decisionId().toString());
        variables.put("args_hash", intent.argsHash());
        variables.put("command_type", intent.command().type());
        variables.put("targets", JSON.writeValueAsString(intent.command().targets()));
        variables.put("rationale", intent.rationale());
        variables.put("audience", audience == null ? "" : audience);
        variables.put("requested_at", now.toString());
        variables.put("expires_at", deadline.toString());
        return variables;
    }

    private void notifyPending(
            DecisionIntent intent, ApprovalSnapshot snapshot, Instant deadline) {
        try {
            notifier.pending(
                    new ApprovalNotification(
                            intent.decisionId(),
                            tenantId,
                            intent.command().type(),
                            intent.argsHash(),
                            intent.rationale(),
                            properties.approverIds(),
                            snapshot.requestedAt(),
                            deadline,
                            snapshot.status()));
        } catch (RuntimeException error) {
            LOG.warn("审批通知失败（审批仍可走 HTTP）: {}", intent.decisionId(), error);
        }
    }

    private void notifyResolved(
            UUID decisionId, ApprovalSnapshot before, ApprovalSnapshot after) {
        try {
            notifier.resolved(
                    new ApprovalNotification(
                            decisionId,
                            tenantId,
                            commandTypeOf(decisionId),
                            after.argsHash(),
                            rationaleOf(decisionId),
                            properties.approverIds(),
                            before.requestedAt(),
                            deadlineOf(before),
                            after.status()));
        } catch (RuntimeException error) {
            LOG.warn("审批结果通知失败: {}", decisionId, error);
        }
    }

    private String commandTypeOf(UUID decisionId) {
        return recordedIntent(decisionId).command().type();
    }

    private String rationaleOf(UUID decisionId) {
        return recordedIntent(decisionId).rationale();
    }

    private static UUID requiredTenant(UUID tenant) {
        if (tenant == null) {
            throw new IllegalStateException(
                    "seedj.ai.plane.tenant 未配置：审批的租户由配置钉死，不从请求读取");
        }
        return tenant;
    }

    private static UUID requiredUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("seedj.ai.identity.on-behalf-of 未配置");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "seedj.ai.identity.on-behalf-of 不是 UUID: " + value, error);
        }
    }
}
