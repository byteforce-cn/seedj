/**
 * 工作流内存实现（参照实现）。
 *
 * <p>覆盖 v2.4 全部 21 项能力，重点验证：构造器无静默修正、死锁检测活跃态定义、
 * 撤回中间任务事件、{@code UrgeAction} 终态语义、{@code ResumeWorkflowAction}、
 * {@code AFTER_ADDED} 默认 {@code AnyApprove}、{@code TRANSFERRED} 不携带 record、
 * {@code onBehalfOf} 仅在 {@code ApproveRecord}。
 *
 * <p>{@code ACTIVITY}/{@code COMPENSATION} 没有外部执行器契约，本实现立即完成；
 * 需要真实执行器的场景应使用 Temporal/Flowable/Camunda 适配器。
 */
package cn.byteforce.seedj.workflow.memory;
