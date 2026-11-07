# 概念与术语

本页钉死容易漂移的术语。术语与代码冲突时，先改代码或先改本页，不允许两边各说一套。

## 命令（command）

一次业务意图，走 `POST /commands/{type}`，必须有 `idempotency_key` 与权限。命令被接受后
返回 `locator` 与 `version`；被拒绝是 4xx，不是 5xx。

## 双时间轴（bitemporal）

- **业务时间**（`valid_from` / `valid_to`）：这条数据在现实世界里什么时候生效。
- **系统时间**（`recorded_at` / `superseded_at`）：系统什么时候记录/替换它。

两者组合允许「查询某业务时点上、系统在某时刻知道的状态」。读接口的 `as_of` 按业务时间，
`recorded_as_of` 按系统时间。

## CQRS

> Seedj 的 CQRS 指「命令/查询 API 分离 + 双时间轴」；读侧直接读对象表，没有投影追平，
> `READ_MODEL_NOT_READY` 只适用于事件溯源路径。

这不是行业里常说的「写模型 + 读模型 + 最终一致」。看到 `CQRS` 时按上面的定义理解；
需要真正的读模型投影时，看[决策指南](decision-guide.md)的复杂域路径。

## 默认路径 / 复杂域路径

- **默认路径**：`CommandRuntime` + 双时间轴对象表。业务对象只有一份字段声明
  （`@SeedjObject` record），命令经过幂等、权限、脱敏、outbox 与审计。
- **复杂域路径**：`CommandAgent` + `EventSourcedRepository`。适用于账务、库存、强一致状态机；
  平台能力差异见[决策指南](decision-guide.md)。

## 对象 / 命令 / 事件

- **对象（object type）**：一个业务实体类型，如 `employee`；对应一张双时间轴表。
- **命令（command）**：一次意图，必须有幂等键与权限。
- **事件（event）**：命令被接受后的事实，写入 outbox；`writeOnly` 与敏感字段默认不进入事件。

## 读模型 / 视图

- **视图（view）**：`/objects/{type}/{id}` 返回的字段投影；由字段策略裁剪与打码。
- **读模型（read model）**：只有事件溯源路径才有「投影追平」意义上的读模型。

## 迁移

- **V1**：签入的 literal SQL，冻结后不再修改。
- **V2/V3…**：新增迁移；由 `seedj-codegen` 生成候选，人工审阅后提交。
- **checksum**：迁移内容的规范化 SHA-256；已应用迁移内容变化会让启动失败。

## 契约层零 Spring

`core` 只依赖 Java 与 Jackson；业务规则测试用 `CommandHarness`（内存存储 + 固定时钟 +
指定调用方），不启动容器。`@SpringBootTest` 只留给 HTTP、安全与数据库契约。
