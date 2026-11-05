package cn.byteforce.seedj.workflow.spi;

/**
 * 任务分配六态。
 *
 * <p>活跃：{@code PENDING}/{@code SUSPENDED}/{@code DELEGATED}；准活跃：{@code TRANSFERRED}；
 * 终态：{@code COMPLETED}/{@code CANCELLED}。死锁检测基于"无活跃态"。
 */
public enum AssignmentStatus {
    PENDING, COMPLETED, SUSPENDED, CANCELLED, TRANSFERRED, DELEGATED
}
