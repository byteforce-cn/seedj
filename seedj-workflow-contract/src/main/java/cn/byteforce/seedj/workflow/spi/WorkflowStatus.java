package cn.byteforce.seedj.workflow.spi;

/** 实例状态；{@code COMPLETED}/{@code FAILED}/{@code CANCELLED}/{@code TERMINATED} 为终态。 */
public enum WorkflowStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPENSATING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TERMINATED
}
