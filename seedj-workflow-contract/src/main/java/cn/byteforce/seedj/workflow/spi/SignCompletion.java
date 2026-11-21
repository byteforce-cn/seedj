package cn.byteforce.seedj.workflow.spi;

/**
 * 会签/或签完成规则。
 *
 * <p>计数口径见规范 §1.5.1：分母为 {@code PENDING + COMPLETED + SUSPENDED}， 分子为 {@code
 * COMPLETED(APPROVE)}；百分比一律 {@code ceil}。
 */
public sealed interface SignCompletion
        permits AllApprove, AnyApprove, AtLeastCount, AtLeastPercentage {}
