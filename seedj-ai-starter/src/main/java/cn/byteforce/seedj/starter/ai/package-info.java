/**
 * AI 装配：决策账本接入、DecisionToken、执行侧 guard、决策面与有界 agent 循环。
 *
 * <p><b>职责</b>：把 {@code core} 的 AI 契约（{@code cn.byteforce.seedj.ai}）与实现（内存 / PostgreSQL）装配成可运行能力，
 * 提供 {@code @EnableAIServer} 开关、{@code seedj.ai.*} 配置与 HTTP 入口。
 *
 * <p><b>依赖方向</b>：依赖 {@code core}、{@code seedj-biz-starter}、{@code seedj-infra-local} 与 {@code
 * seedj-jdbc-postgresql}；禁止依赖任何宿主模块，也禁止被 {@code seedj-biz-starter} 反向依赖。
 */
package cn.byteforce.seedj.starter.ai;
