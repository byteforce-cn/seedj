package cn.byteforce.seedj.workflow.spi;

/** 任意一个 APPROVE 即满足。 */
public record AnyApprove() implements SignCompletion {
}
