/**
 * agent 循环：有界步数、每步一次决策、工具调用只走 HTTP。
 *
 * <p><b>职责</b>：把 {@code core} 的 AI 契约（{@code Proposal} / {@code DecisionKind} / {@code CommandRef}）
 * 与决策面、命令面的 HTTP 面接起来——{@link BoundedLoop} 每步先问 LLM 端口，再把提案交给决策面求值，拿到放行才用
 * DecisionToken 调工具；{@code read} 提案只进观测，{@code finish} 直接收尾。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 的 AI 契约、本模块的 {@code ai} 配置与 {@code ai.guard} 的头名常量；
 * <b>不依赖</b> {@code ai.plane} 的实现（本包只通过 HTTP 线格式与决策面交互，同进程部署也不共享服务对象），也禁止被
 * {@code plane} / {@code guard} 反向依赖。授权判定在决策面，本包只消费 {@code DecisionGrant}。
 *
 * <p><b>T0 的范围</b>：循环只对写动作（{@code EXECUTE_TOOL}）走决策面；只读/追问/升级/收尾是循环内转换，不落账。
 * {@code ToolInvoker.read} 供宿主直接驱动，循环不执行查询。
 */
package cn.byteforce.seedj.starter.ai.runtime;
