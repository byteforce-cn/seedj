package cn.byteforce.seedj.workflow.spi;

/** 工作流契约异常根类型。 */
public class WorkflowException extends RuntimeException {
    public WorkflowException(String message) { super(message); }
    public WorkflowException(String message, Throwable cause) { super(message, cause); }
}
