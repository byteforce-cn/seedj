package cn.byteforce.seedj.workflow.spi;

/** 工作流/步骤超时。 */
public class WorkflowTimeoutException extends WorkflowException {
    public WorkflowTimeoutException(String message) {
        super(message);
    }
}
