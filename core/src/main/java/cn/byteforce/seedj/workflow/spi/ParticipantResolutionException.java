package cn.byteforce.seedj.workflow.spi;

/** 表达式参与者解析失败。 */
public class ParticipantResolutionException extends WorkflowException {
    public ParticipantResolutionException(String message) { super(message); }
    public ParticipantResolutionException(String message, Throwable cause) { super(message, cause); }
}
