package cn.byteforce.seedj.workflow.spi;

/** 人工任务不存在。 */
public class HumanTaskNotFoundException extends WorkflowException {
    public HumanTaskNotFoundException(String message) { super(message); }
}
