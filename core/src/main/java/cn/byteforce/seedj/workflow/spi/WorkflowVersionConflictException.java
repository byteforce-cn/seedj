package cn.byteforce.seedj.workflow.spi;

/** {@code start} 重复 {@code instanceId} 但定义版本不同。 */
public class WorkflowVersionConflictException extends WorkflowException {
    public WorkflowVersionConflictException(String message) {
        super(message);
    }
}
