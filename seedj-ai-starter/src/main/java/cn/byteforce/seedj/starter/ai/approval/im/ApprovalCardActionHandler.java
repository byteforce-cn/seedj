package cn.byteforce.seedj.starter.ai.approval.im;

import cn.byteforce.seedj.ai.ApprovalSnapshot;
import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import cn.byteforce.seedj.starter.ai.approval.ApprovalAction;
import cn.byteforce.seedj.starter.ai.approval.ApprovalService;
import cn.byteforce.seedj.starter.im.ImCardActionContext;
import cn.byteforce.seedj.starter.im.ImCardActionHandler;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批卡片动作：调用与 HTTP 端点同一条 {@link ApprovalService#act} 路径。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public final class ApprovalCardActionHandler implements ImCardActionHandler {

    private final ApprovalService approvals;

    public ApprovalCardActionHandler(ApprovalService approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    public String schema() {
        return ApprovalCardNotifier.SCHEMA;
    }

    @Override
    public Map<String, Object> handle(ImCardActionContext context) {
        Object decision = context.card().get("decision_id");
        Object actionId = context.action().get("id");
        if (decision == null || actionId == null) {
            throw new ContractException(
                    ContractErrorCode.INVALID_PAYLOAD, "卡片缺少 decision_id 或 action id");
        }
        UUID approver;
        try {
            approver = UUID.fromString(context.caller().id());
        } catch (IllegalArgumentException error) {
            throw new ContractException(
                    ContractErrorCode.FORBIDDEN, "审批人 id 不是 UUID: " + context.caller().id());
        }
        ApprovalAction action;
        try {
            action = ApprovalAction.fromWire(String.valueOf(actionId));
        } catch (IllegalArgumentException error) {
            throw new ContractException(ContractErrorCode.INVALID_PAYLOAD, "未知审批动作: " + actionId);
        }
        ApprovalSnapshot snapshot =
                approvals.act(UUID.fromString(String.valueOf(decision)), action, approver, null);
        return Map.of(
                "status", snapshot.status().wire(),
                "decision_id", snapshot.instanceId());
    }
}
