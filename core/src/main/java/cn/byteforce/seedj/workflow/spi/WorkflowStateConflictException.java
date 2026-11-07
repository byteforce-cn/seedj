package cn.byteforce.seedj.workflow.spi;

/** 非法状态转换、能力不支持或乐观锁冲突。 */
public class WorkflowStateConflictException extends WorkflowException {
    public WorkflowStateConflictException(String message) {
        super(message);
    }

    public WorkflowStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
