package cn.byteforce.seedj.workflow.spi;

/** 驳回目标不在 {@code rejectTargets} 白名单内。 */
public class RejectTargetNotAllowedException extends WorkflowException {
    public RejectTargetNotAllowedException(String message) {
        super(message);
    }
}
