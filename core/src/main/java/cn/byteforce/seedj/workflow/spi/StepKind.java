package cn.byteforce.seedj.workflow.spi;

/** 步骤类型；均由 Provider 调度。 */
public enum StepKind { ACTIVITY, HUMAN_TASK, WAIT_SIGNAL, COMPENSATION }
