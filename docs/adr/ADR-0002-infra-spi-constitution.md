# ADR-0002：基础设施 SPI 统一（pki / transit / eventbus / id）

- 状态：accepted
- 日期：2026-11-04
- 决策者：seedj 基础设施维护者
- 关联：`docs/spi/README.md`、`docs/assessment/2026-11-04-infra-spi-consistency.md`、`docs/adr/ADR-0001-pki-spi-single-surface.md`

## 1. 背景

四个能力的对外契约各自发明了一套形状：pki 只有接口 + `@Bean`；transit 只有接口（连 bean 都没有，
业务代码 `new BcTransitProvider()`）；eventbus 用 `ServiceLoader` + 反射构造，且把 relay 的
claim/lease/heartbeat 暴露成业务契约；ID 生成散落在 `domain.IdGenerator`（无人使用）。

实测的后果（非风格问题）：

1. 只依赖 `domain-infra-memory` 的应用启动即失败（`No SPI found for OutboxProviderFactory`）；
2. `OutboxRelayTask` 标了 `@Scheduled` 但全仓库没有 `@EnableScheduling`，事件永久停在 `PENDING`；
3. `Heartbeat.lost` 是创建时的 boolean 副本，租约丢失守卫恒为 false；
4. outbox 的 claim SQL 使用 `UNION ALL` + `FOR UPDATE`，PostgreSQL 直接报错
   （`FOR UPDATE is not allowed with UNION`）——因为从未在真实数据库上跑过；
5. 仓库里没有任何 outbox DDL，业务方必须从 SQL 反推建表。

## 2. 决策

1. **统一业务入口**：`PkiProvider`、`TransitProvider`、`EventPublisher`、`IdGenerator`。
   `EventPublisher` 是新加的意图层；`OutboxStore` / `EventBusTransport` 下沉为机制层。
2. **统一错误模型**：`core` 提供 `InfrastructureException` + `ErrorCategory` + `ErrorDescriptor`；
   每个能力一个错误码枚举。契约边界禁止抛裸 `IllegalStateException`。
3. **统一装配**：删除 `ServiceLoader` 与 `ProviderFactory`；实现模块自带 `@AutoConfiguration`，
   `@ConditionalOnMissingBean` 让业务覆盖；多实现用 bean 名 + `seedj.<cap>.provider`。
4. **EventBus 拆成三层**：
   - 业务：`core.eventbus.EventPublisher` / `EventSubscriber`；
   - 机制：`core.eventbus.spi.OutboxStore` / `EventBusTransport` / `EventConsumerStore`；
   - 装配：`starter`（`EventPublisher`、relay、`EventConsumerContainer`、topic capability、
     initializer；relay/consumer 自持调度线程，不依赖 `@EnableScheduling`）。
   `domain-eventbus-spring` 已删除：它只做装配，没有独立生命周期。
5. **队列与 outbox 分属两个模块**：磁盘/内存队列在 `domain-infra-memory`，
   事务性 outbox 在 `domain-jdbc-postgresql`；两者都缺失时 `EventPublisher` 仍可注入，
   `publish` 抛 `NO_TRANSPORT`，启动不受影响。
6. **资源由启动期能力保证**：`InfrastructureInitializer` 按 order 执行
   `InfrastructureCapability`（探针 → 初始化 → 校验），required 失败阻断启动。
   outbox DDL 改为版本化 `Migration`（version + SHA-256 checksum + `seedj_schema_history`
   + PostgreSQL advisory lock），**不引入 Flyway**；队列 topic 由 `eventbus-topics` 能力确保存在。
   不再有 `schema-initialize` 开关，也不再把裸 SQL 当作一次性的启动脚本。
7. **ID 生成**：`IdGenerator.id()`（雪花）；多服务通过共享文件领取不同节点编号。
8. **Transit 补磁盘**：密钥材料持久化到状态文件，同机多服务共享一个文件即共用密钥与版本历史。
9. **删除死代码，保留生命周期**：删除 `SeedjConfiguration`、`Ports` / `Models` /
   `ProviderFactory`、`domain.IdGenerator`；**保留并补完** `InfrastructureInitializer`、
   `InfrastructureCapability`、`Migration` / `MigrationChecksums`——它们负责保证所有业务应用
   必需的数据库表与队列 topic 一定存在。

## 3. 兼容性

`1.0.0` 未发布，无已发布 API 基线，因此包移动与删除不构成 breaking：

| 变更 | 影响 |
|---|---|
| `cn.byteforce.seedj.transit.spi.*` → `cn.byteforce.seedj.transit.*` | 机械 import 替换 |
| `ErrorCode` → `TransitErrorCode` | 机械替换 |
| `Ports.OutboxProvider` → `eventbus.spi.OutboxStore`（`save` → `append`） | 适配器实现方 |
| `Models.DomainEvent` 删除 | 无实现、无引用 |
| `OutboxMessage` 去掉 `sequence`（由 store 分配） | 业务方少填一个字段 |
| `domain-infra-memory` 新增 `EventBusTransport` / `IdGenerator` bean | 无需配置 |

## 4. 验证

| 变更 | 验证 |
|---|---|
| 契约语义 | `core` test-jar 的 5 个 `*ContractTest` 抽象类，实现模块各 `extends` 一行 |
| 装配隔离 | 每模块 `ApplicationContextRunner` 测试（含"无实现不崩"） |
| 真实数据库 | `PostgresOutboxStoreContractTest`（Testcontainers，11 个用例） |
| 迁移 | `JdbcMigrationRunnerTest`（幂等、checksum、未知版本、并发、回滚） |
| 消费组 | `EventConsumerStoreContractTest`（file/memory）+ `FileEventConsumerGroupTest`（同机多实例） |
| 端到端 | `EventBusIT`、`EventConsumeIT`、`TransitIT`、`IdIT`（真实 HTTP + PostgreSQL + 文件系统） |
| 形状回归 | `SpiSurfaceTest`（6 个包的公开类型白名单） |

命令：

```bash
mvn -o -pl starter -am verify
```

## 5. 后续修订

- 2026-11-05（ID 契约修订）：ID 契约为 `IdGenerator<ID>` + `Identified<ID>`；Spring 默认 bean 为 `IdGenerator<String>`（雪花经 `StringIdGenerator` 适配），业务代码不再直接使用 `UUID.randomUUID()`。
- 2026-11-06（被 ADR-0005 修订）：删除 `Identified<ID>`（零消费者）；业务标识改由泛型参数携带（`Repository<T, ID>` / `EventStore<ID>` / `StreamId<ID>` / `IdCodec<ID>`）；`IdCodec` 是唯一转换点；`eventId` 等消息标识保持 `String` 并明确与业务标识分离。
- 2026-11-05（ADR-0004）：outbox 从 EventBus 中独立为一级能力。`OutboxStore` / `Claim*` / `OutboxFailure` 迁到 `cn.byteforce.seedj.outbox.spi`；EventBus 只保留 transport / consumer 契约，并通过 `EventBusOutboxSink` 接入 outbox。本 ADR 的 eventbus 三层拆分仍成立，outbox 的归属以 ADR-0004 为准。
