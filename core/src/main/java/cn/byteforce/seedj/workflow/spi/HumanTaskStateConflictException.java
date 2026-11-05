package cn.byteforce.seedj.workflow.spi;

/** 任务已终态或当前状态不接受该动作。 */
public class HumanTaskStateConflictException extends WorkflowException {
    public HumanTaskStateConflictException(String message) { super(message); }
}
