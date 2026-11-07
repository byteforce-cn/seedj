# 决策指南：能力矩阵（默认路径 vs 复杂域路径）

设计取舍：`CommandAgent` / `EventSourcedRepository` / `AbstractAggregateRoot` 保留在
`core`，但要逐项说清两条写路径**平台保证了什么**。advanced 路径已接入统一命令
信封；两条路径现在共用 `/commands/{type}` 与同一套幂等、权限、脱敏、终态记录。

> 名词：「CQRS」在本项目指**命令/查询 API 分离 + 双时间轴**，读侧直接读对象表，没有投影
> 追平；`READ_MODEL_NOT_READY` 只适用于事件溯源路径。见 [概念与术语](concepts.md)。

## 1. 能力矩阵

| 能力 | 默认路径（`CommandRuntime` + 双时间轴） | 复杂域路径（`CommandAgent` 事件溯源） | 可复核证据 |
|---|---|---|---|
| 统一写入口 `/commands/{type}` | ✅ | ✅（同一入口，agent 作为执行模型） | `CommandControllerAdvancedTest`、`seedj-it` |
| 幂等键 + 指纹冲突检测 | ✅ | ✅ | `CommandRuntimeTest`、`EventSourcedCommandRuntimeTest` |
| 命令级权限（默认拒绝） | ✅ `CommandDescriptor.permission` | ✅（同一 `CommandCatalog`） | `CommandCatalogTest`、`EventSourcedCommandRuntimeTest` |
| 行范围（scope）写入检查 | ✅ | ✅（当前状态与结果状态；访问器缺失失败关闭） | `AggregateScopeTest`、`EventSourcedCommandRuntimeTest` |
| 命令记录脱敏 | ✅ `CommandPayloadRedactor` | ✅（同一信封；`writeOnly` + `redactedFields`） | `CommandRuntimeRedactionTest`、`EventSourcedCommandRuntimeTest` |
| 未知字段 400 | ✅ schema 白名单（payload + handler 产出） | ✅（解码前 schema + 命令专用字段） | `CommandRuntimeTest`、`EventSourcedCommandRuntimeTest` |
| `writeOnly` 不进入事件/读投影 | ✅ | ✅（事件携带不可见字段即失败关闭） | `EventVisibilityTest`、`EventSourcedCommandRuntimeTest.eventVisibilityFailureRollsBackStreamAndReleasesKey`、`HrEmployeeSampleTest` |
| outbox 与业务写入同事务 | ✅ | ✅（`publisher.publish` 与保存同事务） | `TemporalCommandTransactionTest`、`EventBusIT` |
| 命令/事件一致审计 | ✅ `TemporalCommandAudit` | ✅ `CommandAudit`（accepted 在事务内） | `TemporalCommandAuditTest`、`CommandAgentAuditTest` |
| 任意时点读（`as_of` / `recorded_as_of`） | ✅ | ❌ 需要重放或快照，未交付 | `HrServerPostgresIT` |
| 事件重放 / 事件版本治理 | ❌（不是这一路径的职责） | ✅ | `CommandAgentTest`、`PostgresEventStoreContractTest`、`HrOnboardingPostgresTest` |
| 读模型投影追平 | ❌ 不需要 | ✅ 事件 + outbox → 订阅方 | `EventConsumeIT` |

> advanced 回执用 `target_ref`（`locator` 为空），`event_ids` 由 agent 在事务内分配
> UUID，并通过 `EventPublisher.publish(event, eventId)` 与 outbox message_id 对齐；
> 生产事件存储已交付：`PostgresEventStore`（`event_stream`、版本 CAS、重开重放、
> 并发裁决、与命令事务同生共死），见
> 参考实现见 `HrOnboardingPostgresTest`。
> 快照与“任意时点读”仍不在 advanced 写路径内。

图例：✅ 平台保证；⚠️ 部分保证或依赖调用方；❌ 不提供（必须在设计评审记录补偿手段）。

## 2. 选择规则

1. 只需要「查任意时点的当前状态」→ 默认路径。
2. 需要「从历史事件重建、审计每一次意图」→ 先确认 `recorded_as_of` + 审计链是否够；
   不够再选 event-sourced。
3. 跨聚合强一致投影 → event-sourced + outbox + 重放演练；写信封与生产 `EventStore`
   已由平台保证，「任意时点读」仍需投影/快照补齐。
4. 没有事件版本治理经验 → 默认路径；不要用事件溯源做「以后可能需要」的投机。
5. **不要声称两条路径等价**：矩阵里的 ❌ 是契约，不是待办清单的装饰。

## 3. 数据结构与查询

| 需求 | 用什么 | 不用什么 |
|---|---|---|
| 单表等值 + 排序 + 分页（约 80%） | `TemporalQueryRepository<T>` | 手写 `JdbcTemplate` |
| join / 聚合 / 特殊排序 | `QuerySpec`（reviewed SQL，网关注入 scope/脱敏/审计） | `TemporalStore`、字符串拼 SQL |
| 字段声明 | `@SeedjObject` record（`seedj-codegen` 生成 schema；`view = true` 再生成读视图） | 手写 `ObjectSchema.builder` 或第二份 view record |
| 新版本迁移 | `seedj-codegen` 生成候选 + 人工审阅后提交 V2/V3 | 修改已冻结的 V1 |

## 4. 相关文档

- [概念与术语](concepts.md)
- [命令契约](command-contract.md)
- [读模型](read-model.md)
