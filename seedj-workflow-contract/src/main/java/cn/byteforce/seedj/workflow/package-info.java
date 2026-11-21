/**
 * 工作流契约模块：{@code cn.byteforce.seedj.workflow.spi} 的值类型与端口。
 *
 * <p><b>职责</b>：把工作流定义、实例、任务与动作的冻结契约从 {@code core} 拆成可独立演进的 制品；实现方（{@code seedj-workflow-local}、未来的
 * JDBC provider）只依赖本模块。
 *
 * <p><b>依赖方向</b>：仅 JDK；不依赖 {@code core}、Spring、适配器或任何实现模块。包名与 源码形状保持不变，拆分只改 Maven 制品。
 */
package cn.byteforce.seedj.workflow;
