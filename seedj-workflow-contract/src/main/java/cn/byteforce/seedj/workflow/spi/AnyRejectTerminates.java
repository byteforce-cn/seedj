package cn.byteforce.seedj.workflow.spi;

/** 任一 REJECT 立即终止任务并回退。 */
public record AnyRejectTerminates() implements RejectCompletion {}
