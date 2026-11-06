/**
 * 业务服务装配：安全、命令、查询、审计、事件总线与可观测性。
 *
 * <p><b>职责</b>：把 {@code core} 契约与实现模块装配成业务应用可直接依赖的 starter，
 * 并提供 {@code /commands}、{@code /objects} 与审计/事件基础设施。</p>
 *
 * <p><b>依赖方向</b>：依赖 {@code core}、{@code seedj-infra-local}、{@code seedj-jdbc-postgresql}
 * 的契约实现；禁止依赖 IAM starter 的实现（授权契约在 {@code core}）。</p>
 */
package cn.byteforce.seedj.biz;
