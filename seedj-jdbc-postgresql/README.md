# domain-jdbc-postgresql

PostgreSQL 事务性 outbox 与版本化迁移。业务方通过 `seedj-outbox-starter`（或聚合 `starter`）使用，不直接依赖本模块。

## 提供的能力

| 类型 | 说明 |
| --- | --- |
| `PostgresOutboxStore` | `OutboxStore` 实现：事务内 append、分区有序 claim、租约、重试、死信、运维 retry/discard |
| `PostgresOutboxSchemaCapability` | `InfrastructureCapability`：启动期执行 outbox 迁移并校验 |
| `JdbcMigrationRunner` | 最小迁移执行器：version + SHA-256 checksum + `seedj_schema_history` + advisory lock |
| `PostgresOutboxMigrations` | V1：`outbox` / `outbox_audit` / 索引；V2：列名通用化（`message_id` / `destination` / `content_type` / `content_version`） |

## 迁移语义（不引入 Flyway）

- 迁移按 `version` 升序执行，已应用版本不重复执行；历史表主键是 `(scope, version)`，
  每个能力一个 scope（outbox 使用 `outbox`），版本号互不干扰；
- 已应用迁移的 checksum 与当前代码不一致 → 拒绝启动；
- 数据库中存在本地代码不认识的版本 → 拒绝启动；
- 迁移整体在一个事务内执行，失败回滚；
- 多实例同时启动时用 PostgreSQL advisory lock 串行化，只有一个实例执行。

表结构由 `InfrastructureInitializer` 在启动期调用 `PostgresOutboxSchemaCapability` 保证。

## 配置

```yaml
seedj:
  outbox:
    enabled: true
    provider: postgres
    store:
      append-max-attempts: 3
```

## 测试

- `JdbcMigrationRunnerTest`：幂等、checksum、未知版本、并发、回滚；
- `PostgresOutboxStoreContractTest`：`OutboxStoreContractTest` 的 PostgreSQL 实现；
- `PostgresOutboxAutoConfigurationTest`：有/无 DataSource、迁移幂等、V2 列重命名、事务外 append；
- `PostgresOutboxPackageIndependenceTest`：outbox JDBC 包不 import eventbus。

```bash
mvn -o -pl domain-jdbc-postgresql -am test
```
