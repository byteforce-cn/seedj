/**
 * 决策面：intent 先行、只读不签 token，结局由链上事实派生（spec §5.1 只读路径、§6.2 账本记录、§6.3 链上条目现状、
 * §6.4 两段式与对账、§7.1 claim）。
 *
 * <p><b>职责</b>：把「agent 想做这一步」变成一条已落账的决策，并只在 {@code execute_tool} 且放行时签发
 * DecisionToken。{@code DecisionService} 是判定与顺序的唯一实现：先 append intent，再写 deny 结局 / 只读结局，
 * 或者签 token——签发失败不回滚意图。{@code CapabilityRegistry} 把 {@code seedj.ai.contracts[]} 变成越权判定的
 * 唯一依据，并在启动期与工具注册表交叉校验。{@code DecisionController} 提供 {@code POST /v1/decisions}、
 * {@code GET /v1/decisions/{id}} 与 {@code POST /v1/decisions/{id}/outcome}；{@code JwksController} 发布签名
 * 公钥。效果的收尾分两段：{@code OutcomeDeriver} 把 runtime 上报（只有 {@code command_id}）按链上事实变成一条
 * {@code outcome}，上报阶段不写 {@code audit_entry_id}；{@code ReconciliationJob} 事后按链条目补
 * {@code reconciled}，对账入口是 {@code PlaneReconciliationSource}（T0 默认实现只 WARN、不扫描，见其 Javadoc）。
 * 本包<b>不</b>执行命令、<b>不</b>校验 token、<b>不</b>做审批（T0 无审批）——那是执行侧 guard 与后续任务的事。
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 的 AI 契约（{@code DecisionIntent}/{@code DecisionTokenClaims}/
 * {@code DecisionLedger}）、{@code ai.token} 的签发方、{@code seedj-biz-starter} 的错误信封、宿主的
 * {@code Clock} 与可选的业务库 {@code DataSource}（只读：查 {@code command_record} / {@code audit_entry}）；
 * 禁止依赖具体存储实现（账本只经 SPI），也禁止被宿主的业务包反向依赖。装配集中在
 * {@code ai.autoconfigure.DecisionPlaneAutoConfiguration}，并同时受 {@code AiEnabledMarker} 与
 * {@code seedj.ai.plane.enabled} 两道门控制。
 */
package cn.byteforce.seedj.starter.ai.plane;
