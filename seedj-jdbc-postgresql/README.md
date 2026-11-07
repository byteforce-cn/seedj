# seedj-jdbc-postgresql

PostgreSQL 适配器与版本化迁移。业务代码通过 starter 使用本模块，不直接依赖实现类型。

## 包结构

```text
cn.byteforce.seedj.infra.jdbc                 迁移执行器与 JDBC 公共支持
cn.byteforce.seedj.infra.jdbc.outbox          事务性 outbox（含 relay 装配）
cn.byteforce.seedj.infra.jdbc.audit           审计哈希链
cn.byteforce.seedj.infra.jdbc.cqrs            命令记录 / 事件存储（event_stream）
cn.byteforce.seedj.infra.jdbc.domain          双时间轴对象存储与 DDL
cn.byteforce.seedj.infra.jdbc.lock            租约表分布式锁
cn.byteforce.seedj.infra.jdbc.clock           HLC 状态与全局序列
cn.byteforce.seedj.infra.jdbc.iam             IAM 存储
```

## 迁移语义（不引入 Flyway）

- 迁移按 `version` 升序执行，已应用版本不重复执行；历史表主键是 `(scope, version)`，
  每个能力一个 scope，版本号互不干扰；
- 已应用迁移的 checksum 与当前代码不一致 → 拒绝启动；
- 数据库中存在本地代码不认识的版本 → 拒绝启动；
- 迁移整体在一个事务内执行，失败回滚；
- 多实例同时启动时用 PostgreSQL advisory lock 串行化，只有一个实例执行。

各表的建表由对应 `InfrastructureCapability` 在启动期保证（outbox、审计、命令、
对象、锁、时钟、IAM、事件流各自 order）。事件流迁移 scope 为 `event`，
`EventSchemaCapability` 的 order 为 140。

## 事件存储

`PostgresEventStore<ID>` 实现 `EventStore<ID>` 契约：

- `event_stream` 以 `(aggregate_type, stream_key, version)` 唯一，版本从 1 连续递增；
  `position` 是全局写入顺序，留给后续投影；
- `append` 在事务内校验流头（`MAX(version)` 与 `COUNT(*)` 必须一致），再批量写入；
  并发追加由唯一约束裁决，输家得到 `CONCURRENCY_CONFLICT`，不会留下半条流；
- payload 由 `EventPayloadSerializer` 编码（默认 Jackson JSON）；逻辑名 → 事件类通过
  `EventTypeRegistry` 解析；
- `PostgresEventStore` 实现 `EventTypeRegistrar`，`EventSourcedRepository.load` 会在
  读取前把聚合 `apply` 覆盖的事件类型（含 sealed permitted 子类型）注册进去，
  应用重启后第一次读取即可解析；
- 版本空洞、未知事件类型、反序列化失败一律 `CORRUPT_STREAM`；
- `append` 加入调用方事务，在 `CommandAgent` 内与 outbox、审计、命令终态同生共死。

装配示例（业务自己声明带类型参数的 bean）：

```java
@Bean
public EventStore<EmployeeId> employeeEventStore(
        JdbcTemplate jdbc, EmployeeIdCodec codec, EventPayloadSerializer serializer) {
    return new PostgresEventStore<>(jdbc, codec, serializer, new EventTypeRegistry());
}
```

不传 `serializer` 时使用内置 Jackson JSON。`seedj-biz-starter` 在
`seedj.event-store.enabled=true`（默认）且 `seedj.event-store.provider=postgres`
（默认）时自动注册 schema capability；表不存在会在启动期失败。

## 配置

```yaml
seedj:
  outbox:
    provider: postgres
    relay: { enabled: true, poll-interval: 1s, lease: 30s }
  audit:
    provider: postgres
  lock:
    postgres:
      enabled: true
  event-store:
    enabled: true
    provider: postgres
```

## 测试

```bash
./mvnw -o -B -pl seedj-jdbc-postgresql -am test
```

覆盖迁移幂等/checksum/未知版本/并发/回滚、outbox 契约、审计链、时间轴、锁契约、
事件存储契约/并发/重开/损坏检测/事务参与（需要 Docker / Testcontainers）。
