/**
 * AI 决策账本的内存实现：本地开发、{@code seedj-it} 夹具与契约测试用的参照实现。
 *
 * <p><b>职责</b>：按 {@code DecisionLedger} 契约在内存里维护按租户分区、只追加的哈希链，语义与 {@code seedj-jdbc-postgresql} 的
 * {@code PostgresDecisionLedger} 一致：记录体由 {@code DecisionRecordBodies} 组装， 哈希由 {@code LedgerHash}
 * 计算，{@code verify} 同样是「先读链头，再按链头截断扫记录」。
 *
 * <p><b>依赖方向</b>：只依赖 {@code core} 与 JDK；禁止依赖 Spring、JDBC 与 starter。状态在堆上，重启即丢， 不用于生产。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.infra.local.ai;
