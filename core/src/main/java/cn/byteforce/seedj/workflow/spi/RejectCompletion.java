package cn.byteforce.seedj.workflow.spi;

/** 驳回终止规则。 */
public sealed interface RejectCompletion permits
        AnyRejectTerminates, RecordAndContinue, AtLeastRejects {
}
