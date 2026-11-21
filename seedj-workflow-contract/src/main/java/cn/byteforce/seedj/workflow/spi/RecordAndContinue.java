package cn.byteforce.seedj.workflow.spi;

/** 记录 REJECT 但不立即终止；规则不可满足时按 §1.5.1.1 进入死锁终态。 */
public record RecordAndContinue() implements RejectCompletion {}
