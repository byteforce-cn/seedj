package cn.byteforce.seedj.workflow.spi;

/** 所有计入分母的参与者都 APPROVE，且不存在 REJECT 记录。 */
public record AllApprove() implements SignCompletion {
}
