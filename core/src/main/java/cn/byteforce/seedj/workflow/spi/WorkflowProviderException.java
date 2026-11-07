package cn.byteforce.seedj.workflow.spi;

/** Provider 底层故障；调用方可重试。 */
public class WorkflowProviderException extends WorkflowException {
    public WorkflowProviderException(String message) {
        super(message);
    }

    public WorkflowProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
