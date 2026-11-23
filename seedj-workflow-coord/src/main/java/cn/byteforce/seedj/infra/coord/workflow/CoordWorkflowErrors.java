package cn.byteforce.seedj.infra.coord.workflow;

import cn.byteforce.coord.sdk.CoordException;
import cn.byteforce.coord.sdk.ErrorCode;
import cn.byteforce.seedj.workflow.spi.AssigneeNotAllowedException;
import cn.byteforce.seedj.workflow.spi.HumanTaskNotFoundException;
import cn.byteforce.seedj.workflow.spi.HumanTaskStateConflictException;
import cn.byteforce.seedj.workflow.spi.ParticipantResolutionException;
import cn.byteforce.seedj.workflow.spi.RejectTargetNotAllowedException;
import cn.byteforce.seedj.workflow.spi.WithdrawNotAllowedException;
import cn.byteforce.seedj.workflow.spi.WorkflowDefinitionNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowException;
import cn.byteforce.seedj.workflow.spi.WorkflowNotFoundException;
import cn.byteforce.seedj.workflow.spi.WorkflowProviderException;
import cn.byteforce.seedj.workflow.spi.WorkflowStateConflictException;
import cn.byteforce.seedj.workflow.spi.WorkflowVersionConflictException;
import java.util.Locale;

/**
 * coord 错误码 → seedj 异常。
 *
 * <p>只读 {@link ErrorCode} 与调用上下文（动作类型），不解析 gRPC 的英文文案；文案只进 cause/消息尾部。
 */
final class CoordWorkflowErrors {

    private static final String CONFIG_HINT =
            " (check coord agent services.workflow_on_behalf_services and"
                    + " seedj.workflow.coord.sign-admin)";

    enum ActionKind {
        VOTE,
        SEAT,
        WITHDRAW,
        DEFINITION,
        ADMIN,
        START,
        READ
    }

    private CoordWorkflowErrors() {}

    static WorkflowException toSeedj(CoordException e, ActionKind kind) {
        ErrorCode code = e.getErrorCode();
        String message = e.getMessage() == null ? code.getProtoName() : e.getMessage();
        return switch (code) {
            case NOT_FOUND -> notFound(message, kind);
            case ALREADY_EXISTS ->
                    kind == ActionKind.DEFINITION || kind == ActionKind.START
                            ? new WorkflowVersionConflictException(message)
                            : new WorkflowStateConflictException(message);
            case FAILED_PRECONDITION ->
                    kind == ActionKind.VOTE || kind == ActionKind.SEAT
                            ? new HumanTaskStateConflictException(message)
                            : new WorkflowStateConflictException(message);
            case INVALID_ARGUMENT -> invalidArgument(message, kind);
            case PERMISSION_DENIED -> permissionDenied(message, kind);
            case UNAUTHENTICATED ->
                    new WorkflowProviderException(
                            "coord rejected the caller identity: " + message + CONFIG_HINT);
            case UNAVAILABLE, DEADLINE_EXCEEDED, NOT_LEADER, AGENT_UNAVAILABLE, INTERNAL ->
                    new WorkflowProviderException("coord workflow call failed: " + message, e);
            default -> new WorkflowProviderException("coord workflow call failed: " + message, e);
        };
    }

    private static WorkflowException notFound(String message, ActionKind kind) {
        return switch (kind) {
            case VOTE, SEAT -> new HumanTaskNotFoundException(message);
            case DEFINITION -> new WorkflowDefinitionNotFoundException(message);
            default -> new WorkflowNotFoundException(message);
        };
    }

    private static WorkflowException invalidArgument(String message, ActionKind kind) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("reached-node whitelist") || lower.contains("target node")) {
            return new RejectTargetNotAllowedException(message);
        }
        if (lower.contains("approve seats")
                || lower.contains("approve.role")
                || lower.contains("approve.approvers")
                || lower.contains("participant")) {
            return new ParticipantResolutionException(message);
        }
        return kind == ActionKind.VOTE || kind == ActionKind.SEAT
                ? new HumanTaskStateConflictException(message)
                : new WorkflowStateConflictException(message);
    }

    private static WorkflowException permissionDenied(String message, ActionKind kind) {
        if (kind == ActionKind.WITHDRAW
                && message.toLowerCase(Locale.ROOT).contains("no approve record")) {
            return new WorkflowStateConflictException(message);
        }
        return switch (kind) {
            case VOTE -> new AssigneeNotAllowedException(message);
            case WITHDRAW -> new WithdrawNotAllowedException(message);
            default ->
                    new WorkflowProviderException(
                            "coord permission denied: " + message + CONFIG_HINT);
        };
    }
}
