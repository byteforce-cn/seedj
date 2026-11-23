/**
 * coord workflow v2 适配器：把 {@code cn.byteforce.seedj.workflow.spi.WorkflowProvider} 映射到 coord
 * 的原生审批面（定义编译成 coord DSL、round 级读面、12 个动作 + cancel/terminate）。
 *
 * <p>装配入口是 {@link cn.byteforce.seedj.infra.coord.workflow.CoordWorkflowAutoConfiguration}，
 * 由 {@code seedj.workflow.provider=coord} 选择；{@code seedj-workflow-local} 仍是默认 provider，
 * 两者并列、不替换。coord 不建模的语义（业务表单、参与者表达式校验、动作前开关）由本模块补齐，
 * 缺口与已知边界见 {@code docs/guide/known-limitations.md}。
 */
package cn.byteforce.seedj.infra.coord.workflow;
