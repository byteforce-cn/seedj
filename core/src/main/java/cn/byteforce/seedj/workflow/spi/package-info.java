/**
 * 工作流 SPI（v2.4 冻结基线）。
 *
 * <p>本包是跨模块、跨团队、跨发布周期的二进制边界，零外部依赖。字段形状自 v2.4 起冻结：
 * 后续新增能力只允许走 {@code default} 方法、{@link WorkflowCapabilities} 新字段、
 * {@code WorkflowAction} sealed permits 追加、或新 {@code V2} record + {@code default} 适配。
 *
 * <h2>两个入口</h2>
 * <ul>
 *   <li>业务方：注入 {@link cn.byteforce.seedj.workflow.spi.WorkflowProvider}，
 *       用 {@code start/act/cancel/terminate/listTasks} 表达意图。</li>
 *   <li>Provider 实现方：实现本包契约，并先用 {@link WorkflowProvider#capabilities()}
 *       声明真实能力。</li>
 * </ul>
 *
 * <h2>与仓库约束的关系</h2>
 * 本包遵循 SPI 宪法：一类型一文件、构造期校验、零 Spring 依赖。
 * 装配不使用 v2.4 草案 §6 的 {@code ProviderFactory}/{@code reflective provider lookup}，
 * 改由实现模块的 {@code @AutoConfiguration} + bean 名 + {@code seedj.workflow.provider}
 * 选择；不使用反射式 provider lookup。
 */
package cn.byteforce.seedj.workflow.spi;
