/**
 * 执行侧 guard：把「已认证调用方 + DecisionToken + 原始命令请求」判成放行或拒绝。
 *
 * <p><b>职责</b>：本包是 spec §7.3 十条校验的执行侧落地——{@code AgentGuardFilter} 只拦单段 {@code POST
 * /commands/*}，把拒绝写成 401（缺凭据，带 {@code WWW-Authenticate: AgentDecision}）或 403/400（带 {@code
 * {code,detail}} 的 JSON 体），放行则不改变下游语义；{@code ConfiguredToolRegistry} 把 {@code
 * seedj.ai.tools[]} 变成启动期校验过的命令白名单。校验结论由 {@code ai.token} 的 {@code
 * DecisionTokenVerifier} 给出，本包不重新判定 token 内容，也不做授权决策。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 的 AI 契约与调用方视图、{@code ai.token} 的签发/校验、{@code
 * seedj-biz-starter} 的命令请求体与命令目录（用于交叉校验，避免手写第二份命令清单）；禁止被宿主的业务包反向依赖，
 * 也禁止依赖具体存储实现。
 */
package cn.byteforce.seedj.starter.ai.guard;
