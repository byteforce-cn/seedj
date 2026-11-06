# 决策指南：temporal 还是 event-sourced

本页只回答一个问题：新业务对象应该走哪条写路径。默认答案永远是 **temporal**；
event-sourced 需要显式理由，并且必须在设计评审里写下来。

## 两条路径

| 维度 | temporal（默认） | event-sourced（`CommandAgent`） |
|---|---|---|
| 真相来源 | 带 `[from, to)` 有效期的对象行 | 事件流 + 聚合重放 |
| 历史查询 | `as_of` / `recorded_as_of` 直接读 | 重放到指定版本 |
| 写入口 | `POST /commands/{type}` + `CommandDescriptor` | 同一入口，但 agent 是事件溯源实现 |
| 适用 | 主数据、配置、组织/岗位、审批结果 | 账务、库存、状态机强一致的核心域 |
| 成本 | 低：一条 append + 派生字段 | 高：事件版本、快照、重放与迁移 |
| 失败模式 | 时点读边界写错 | 事件语义漂移、重放不幂等 |

## 选择规则

1. 需求只要求“能查任意时点的当前状态” → temporal。
2. 需求要求“能从历史事件重建、审计每一次意图” → 先确认 temporal 的
   `recorded_as_of` + 审计链是否够；不够再选 event-sourced。
3. 需要跨聚合的强一致投影 → 事件溯源 + outbox，且必须提供重放演练。
4. 团队没有事件版本治理经验 → temporal；不要用事件溯源做“以后可能会需要”的投机。

## 实施差异

- temporal：只改 `ObjectSchema` 与命令 record；事件、DDL、脱敏由 schema 派生。
- event-sourced：必须单独定义事件版本、聚合重放与快照策略；`CommandAgent`
  的每个动作都要有幂等键测试与重放测试。
- 两条路径共用 `CommandRuntime`、`CommandDescriptor`、权限、错误信封与 outbox，
  不要为 event-sourced 另建 HTTP 入口。

## 相关文档

- [命令契约](command-contract.md)
- [读模型](read-model.md)
- [ADR-0005 CQRS 与身份契约](adr/ADR-0005-cqrs-and-identity-contract.md)
