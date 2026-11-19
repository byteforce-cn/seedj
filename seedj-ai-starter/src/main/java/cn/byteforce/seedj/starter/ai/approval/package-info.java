/**
 * 审批（T2-local）：不可逆动作先落 intent，再启动人工审批，批准后按需签发 DecisionToken。
 *
 * <p><b>职责</b>：{@link cn.byteforce.seedj.starter.ai.approval.ApprovalService} 负责 start / act /
 * grant / expire；{@link cn.byteforce.seedj.starter.ai.approval.ApprovalWorkflowDefinition} 在启动期注册单
 * HUMAN_TASK 的工作流定义；{@link cn.byteforce.seedj.starter.ai.approval.ApprovalNotifier} 是通知端口（默认只记日志， 有
 * IM 时由卡片适配器覆盖）。
 *
 * <p><b>边界</b>：本包只依赖 {@code WorkflowProvider} 端口，不依赖 coord、不依赖 IM、不写 token 到账本；审批快照与 冻结的 {@code
 * argsHash} 是唯一绑定依据。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.starter.ai.approval;
