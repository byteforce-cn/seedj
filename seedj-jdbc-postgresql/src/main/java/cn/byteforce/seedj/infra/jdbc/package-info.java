/**
 * PostgreSQL 适配器与版本化迁移。
 *
 * <p><b>职责</b>：事务性 outbox、命令记录、时间轴对象、审计哈希链、IAM 存储、分布式锁
 * 与时钟状态；同时提供 {@code JdbcMigrationRunner} 与各能力 DDL。</p>
 *
 * <p><b>依赖方向</b>：依赖 {@code core} 契约与 Spring JDBC/JDBC API；禁止依赖 starter
 * 与宿主模块。业务代码通过 starter 使用本模块，不直接依赖实现类型。</p>
 */
package cn.byteforce.seedj.infra.jdbc;
