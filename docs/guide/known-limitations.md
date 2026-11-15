# 已知限制

发布前请先读这一页。它列出「能做什么」之外的边界，避免把样板当成生产系统。

## 单租户

1.0 的 `Caller.tenantId` 只参与 IAM 的角色/绑定解析，业务表没有 `tenant_id` 列。
多租户隔离需要自己在存储层加列并收紧读路径；不要假设跨租户读取会被拒绝。

## 事件存储只在 PostgreSQL

事件溯源写路径的生产实现是 `seedj-jdbc-postgresql` 的 `PostgresEventStore`。内存实现只用于
开发与测试，重启即丢；`local` profile 不提供事件历史。

## `local` 不持久化

`local` 用内存实现命令、对象与锁，审计/outbox/eventbus 关闭。重启清空、单实例。
它只为「本机 5 分钟跑起来」服务。

## IAM 未宣称生产可用

IAM 的退出标准与当前状态见 [IAM 装配 §生产可用性边界](iam-starter.md)。在完成独立安全
评审前，不要在生产环境宣称「生产可用 IAM」。

## 通用读面向生产关闭

`seedj.read.generic-surface` 在 `postgres` 样板里开启，方便调试；生产宿主应保持关闭，
只暴露声明过的 `.query(...)` 路径。

## 工作流

`seedj-workflow-local` 是内存实现；PostgreSQL 侧已固定存储 schema、跨连接可读与 timer
扫描三个语义，完整的 JDBC 状态机（定义注册、动作推进）仍未交付。

## coord 锁依赖实例时钟

`provider=coord` 的分布式锁把 `expiresAt` 写进 coord KV，由**持有方与争抢方的本地时钟**
判断租约是否过期（coord 的锁租约只到秒级，满足不了毫秒 TTL 的契约）。实例间时钟漂移会
缩短或延长互斥窗口；对强一致有要求的场景，仍要在存储侧用唯一约束/条件更新兜底。释放
使用事务读集校验，过期持有者不会误删新持有者的记录。

## coord 消费账本的边界

`seedj.eventbus.transport=coord` 的消费状态在 coord KV 的投递账本里：每次 poll 只扫描前
`consumer-scan-limit` 条（默认 1000，账本大小 ≈ 在飞消息数），死信直接存 KV，因此单条事件
大小受 KV 单值上限约束。到期重投按位点回读 MQ：消息已被 retention 清掉时转入死信，原因记为
`seedj.retention.expired`——这意味着重试窗口不能长于 topic 的保留时间。

## 只有 PostgreSQL 适配器

没有 MySQL / Oracle 适配器。SPI 是开放的，新增适配器的步骤见[基础设施 SPI](spi.md)。

## 1.0.0 尚未发布

线格式（`/commands/{type}`、`/objects/{type}/{id}`、错误信封 `{code,message,details}`）
已冻结；Java API 在 1.0.0 前仍可能调整。制品暂未发布到 Maven Central，使用方需从源码构建。
