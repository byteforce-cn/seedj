# ADR-0004：Outbox 独立为一级基础设施能力

- 状态：accepted
- 日期：2026-11-05
- 决策者：seedj 基础设施维护者
- 关联：`docs/superpowers/specs/2026-11-05-outbox-standalone-spi-design.md`、`docs/spi/README.md`、`docs/adr/ADR-0002-infra-spi-constitution.md`

## 1. 背景

ADR-0002 把 EventBus 拆成"业务入口 / 机制 / 装配"三层，但 outbox 的存储、relay、租约和错误码仍然和 eventbus 共用一个包、一套配置、一组指标：

- `cn.byteforce.seedj.eventbus.spi` 同时有 `OutboxStore`（outbox）与 `EventBusTransport` / `EventConsumerStore`（eventbus）；
- `OutboxMessage` 被 transport、consumer、publisher 共用，名字把 outbox 泄漏到投递侧；
- `EventConsumerStore.retry(..., OutboxFailure)` 让消费侧复用 outbox 的失败类型；
- `EventBusErrorCode` 混合 `TX_REQUIRED` / `LEASE_LOST` / `OUTBOX_UNAVAILABLE` 与 `CONSUMER_FAILED`；
- `EventBusProperties` 一个类装 relay + consumer，`RelayMetrics` 同时统计两侧；
- `starter` 的 `OutboxRelay` 与 `EventConsumerContainer` 在同一包、同一自动配置。

后果：任何非 eventbus 的生产者（workflow、未来的业务模块）想用事务性 outbox，都必须依赖 eventbus 的类型和装配；eventbus 也无法在不带 outbox 的情况下保持清晰边界。

## 2. 决策

1. **Outbox 成为独立能力**，契约放 `core`：
   - 业务入口：`cn.byteforce.seedj.outbox.OutboxWriter`（`append(OutboxRecord)`）；
   - 记录：`OutboxRecord`（`messageId, destination, partitionKey, contentType, contentVersion, payload, headers, occurredAt`），payload 是不透明字节；
   - 机制：`outbox.spi.OutboxStore`（存储/claim/lease）、`OutboxSink`（投递出口）、`Claim*`、`OutboxFailure`；
   - 错误：`OutboxException` + `OutboxErrorCode`。
2. **EventBus 只是 outbox 的第一个投递适配器**：`starter` 的 `EventBusOutboxSink` 把 `OutboxRecord` 映射回 `EventMessage` 再写 `EventBusTransport`；outbox 不认识事件模型。
3. **新增 `seedj-outbox-starter`**（依赖 `core`）：`OutboxWriter` 默认实现、`OutboxRelay`、`OutboxMetrics`、`OutboxInfrastructureInitializer`、自动配置。聚合 `starter` 依赖它，并保留 eventbus 装配。
4. **EventBus 侧去 outbox 化**：
   - `OutboxMessage` → `EventMessage`；
   - 消费失败类型 `OutboxFailure` → `DeliveryFailure`；
   - `EventBusErrorCode` 只保留 transport/consumer 错误（`LEASE_LOST` 保留给消费租约，新增 `TRANSPORT_UNAVAILABLE` 替代文件 transport 误用的 `OUTBOX_UNAVAILABLE`）；
   - `EventBusProperties` 删除 `relay`；`RelayMetrics` 拆成 `EventBusMetrics`（consumer）与 `OutboxMetrics`（relay）。
5. **配置按能力拆分**：`seedj.eventbus.*` 只保留 eventbus；outbox 使用 `seedj.outbox.enabled` / `seedj.outbox.provider` / `seedj.outbox.relay.*` / `seedj.outbox.store.append-max-attempts`。不保留旧键别名（1.0.0 未发布）。
6. **数据库列通用化**：outbox 表增加 V2 迁移，把 `event_id` / `topic` / `event_type` / `event_version` 重命名为 `message_id` / `destination` / `content_type` / `content_version`；scope 从 `eventbus-outbox` 改为 `outbox`。表名 `outbox` / `outbox_audit` 不变。
7. **单 sink**：一个应用一个 `OutboxSink`；多 sink 路由明确不做。需要多目标的场景由应用提供组合 sink。
8. **依赖方向由形状门禁锁住**：`outbox` 包不得 import `eventbus`；`domain-infra-memory` 的 transport/consumer 不得 import `outbox`。测试与 `docs/spi/README.md` §6 的 grep 同时执行。

## 3. 后果

### 正面

- workflow / IAM / 新业务模块可以直接用 `OutboxWriter`，不需要 eventbus 在场。
- outbox 的 relay、指标、迁移、TCK 可以独立测试；`seedj-outbox-starter` 不依赖 eventbus。
- eventbus 的 transport/consumer 契约只描述消息投递，不再出现 outbox 命名。
- 配置/指标/错误码的归属与模块边界一致，排障时不需要跨能力猜。

### 负面 / 代价

- 新增一个 Maven 模块与一层 `OutboxRecord ↔ EventMessage` 映射。
- 跨能力异常：outbox 激活时 `EventPublisher.publish` 可能抛 `OutboxException(TX_REQUIRED)`；业务要能力无关处理时 catch `InfrastructureException`。
- `OutboxFailure` 与 `DeliveryFailure` 保留为两个三字段记录，存在少量重复。
- 配置键与指标名变化，仓库内约 25 处引用一次性迁移。
- `seedj-outbox-starter` 与聚合 `starter` 的 initializer 会重复执行能力探测；`isReady()` 保证幂等。

## 4. 替代方案

- **只拆契约包，relay 留 starter**：改动小，但只用 outbox 仍需拉入聚合 starter（IAM、security、actuator），"独立能力"不成立。
- **泛型 `OutboxStore<T>`**：省映射，但 JDBC 最终仍要字节与序列化器，泛型样板摊到每个适配器和 TCK，收益不抵成本。
- **保留 `seedj.eventbus.outbox.*` 兼容键**：增加长期双写/歧义，1.0.0 未发布没有必要。

## 5. 迁移

| 旧 | 新 |
| --- | --- |
| `eventbus.OutboxMessage` | `eventbus.EventMessage` |
| `eventbus.spi.OutboxStore` / `Claim*` / `OutboxFailure` | `outbox.spi.*` |
| `OutboxStore.append(OutboxMessage)` | `OutboxStore.append(OutboxRecord)` |
| `ClaimedRecord.message()` | `ClaimedRecord.record()` |
| `EventConsumerStore.retry(..., OutboxFailure)` | `EventConsumerStore.retry(..., DeliveryFailure)` |
| outbox 的 `EventBusException` / `EventBusErrorCode` | `OutboxException` / `OutboxErrorCode` |
| `seedj.eventbus.outbox.*` | `seedj.outbox.*` |
| `seedj.eventbus.relay.*` | `seedj.outbox.relay.*` |
| `seedj.eventbus.outbox.append-max-attempts` | `seedj.outbox.store.append-max-attempts` |
| `seedj.eventbus.outbox.claimed` 等指标 | `seedj.outbox.*` |

## 6. 验证

```bash
mvn -o -pl core,domain-infra-memory,domain-jdbc-postgresql,seedj-outbox-starter,starter -am verify
grep -rn "^import cn\.byteforce\.seedj\.eventbus" \
  core/src/main/java/cn/byteforce/seedj/outbox \
  seedj-outbox-starter/src/main \
  domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox
```

结果：core 75、postgres 32、memory 109、IAM 13、outbox starter 11、starter 9 个单测 + 110 个端到端用例全部通过（含 `EventBusIT` / `EventConsumeIT`）。
