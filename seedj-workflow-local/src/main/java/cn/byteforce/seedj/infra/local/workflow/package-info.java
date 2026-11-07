/**
 * 工作流本地内存实现（参照实现）；workflow SPI 仍在 {@code core}。
 *
 * <p><b>职责</b>：交付一个可直接引用的本地 workflow 实现，覆盖 v2.4 的 21 项能力； 不启动 server、不产出可执行 jar、不作为部署单元。生产场景走
 * {@code seedj-workflow-flowable} / {@code seedj-workflow-coord} 的 API 对接适配器。
 *
 * <p><b>依赖方向</b>：只依赖 {@code core} 的 workflow SPI 与 Spring Boot 装配注解 （provided）；不依赖 {@code
 * seedj-infra-local}、JDBC 或任何 server 模块，也不会反向 依赖业务代码。
 *
 * <p>覆盖语义：构造器无静默修正、死锁检测活跃态定义、撤回中间任务事件、 {@code UrgeAction} 终态语义、{@code ResumeWorkflowAction}、{@code
 * AFTER_ADDED} 默认 {@code AnyApprove}、{@code TRANSFERRED} 不携带 record、{@code onBehalfOf} 仅在 {@code
 * ApproveRecord}。{@code ACTIVITY}/{@code COMPENSATION} 没有外部执行器契约， 本实现立即完成。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.infra.local.workflow;
