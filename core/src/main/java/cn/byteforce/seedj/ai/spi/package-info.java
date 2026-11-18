/**
 * AI 命令网关的 SPI 端口：决策账本与工具注册表。
 *
 * <p><b>职责</b>：声明“决策怎么落账、命令的动作分类从哪里查”两件事的接口，不含实现。账本实现分别落在 {@code seedj-infra-local}（内存）与 {@code
 * seedj-jdbc-postgresql}（PostgreSQL），工具注册表由 starter 装配；共同行为由 {@code DecisionLedgerContractTest} 约束。
 *
 * <p><b>依赖方向</b>：只依赖 {@code cn.byteforce.seedj.ai} 的值类型与 JDK；不依赖 Spring、{@code java.sql}
 * 或任何实现模块。实现模块反向依赖这里。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.ai.spi;
