/**
 * 决策账本（{@code cn.byteforce.seedj.ai.spi.DecisionLedger}）的 PostgreSQL 实现。
 *
 * <p>表由 {@link cn.byteforce.seedj.infra.jdbc.ai.AiSchemaCapability} 在启动期创建，迁移 scope 为 {@code ai}；
 * 装配在 {@link cn.byteforce.seedj.infra.jdbc.ai.PostgresAiAutoConfiguration}，本包不依赖 AI starter。
 *
 * <p>依赖方向：只依赖 {@code core} 的账本端口与值类型，以及本模块的 {@code JdbcMigrationRunner}。
 *
 * @author ByteForce
 * @since 1.0.0
 */
package cn.byteforce.seedj.infra.jdbc.ai;
