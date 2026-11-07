package cn.byteforce.seedj.workflow.spi;

/** 人工任务五态；{@code COMPLETED}/{@code CANCELLED}/{@code REJECTED}/{@code WITHDRAWN} 为终态。 */
public enum HumanTaskStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
    REJECTED,
    WITHDRAWN
}
