# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `seedj-infra-coord`（P0）：coord 0.4.3 的 ID、编号、全局序列与 EventBus 发布适配器；
  provider 显式选择，失败消息点名 agent 地址；测试含真集群夹具（Testcontainers）。
- `seedj-infra-coord` 分布式锁：用 coord KV + OCC 事务组合出毫秒级租约（`TxnClient` 读集
  校验 + 记录内 `expiresAt`），`seedj.lock.provider=coord` 显式选择；过期持有者不会误删
  新持有者的记录。
- `seedj-infra-coord` 消费端：`seedj.eventbus.transport=coord` 同时提供 `EventConsumerStore`
  （MQ v2 join/poll/ack + KV 投递账本），支持毫秒级租约、延迟重试、attempts 与按组合查询死信；
  到期重投按位点回读，retention 缺失转死信。
- `seedj-im-starter`（阶段 1）：帧协议、Netty TCP/WebSocket 传输、IAM JWT 握手、心跳与未实现命令 `501`；`seedj-server/im` 改为单类宿主。
- `seedj-it` 的 coord 端到端：`CoordIT` 在真 coord v2 集群（server + agent 容器）上验证 ID、编号、
  全局序列、分布式锁、EventBus 发布/消费与「重试 → 死信」，并核对 coord 侧 topic 与消息。
- 开发者文档站（`docs/`，VitePress）与 GitHub Pages 发布。
- 贡献指南、安全政策、行为准则、支持说明与 issue/PR 模板。

### Changed

- README 改为公开仓门面：可复制的快速开始、业务改动示例与模块表。
- 源码树中的文档引用全部指向仓内 `docs/`；架构决策不再出现在公开树。

### Fixed

- 集群 IT 的服务身份：`spring.application.name` 改由命令行参数注入，不再被各服务的
  `application.yml` 覆盖；coord 消息的 producer 与默认命名空间因此按服务区分，而不是全部
  串到同一个名字。
- `search` 的当前视图快路径补上 `valid_to IS NULL`，与 `find` 共用同一口径：关闭业务时间轴（软删/归档）的对象不再在列表里复活，`find` 与 `search` 结论一致；`uq_*_current` 仍只约束未封口行，重新打开通过先封口再追加实现。
- IAM `store=jdbc` 的 refresh_token 交换不再 500：claims/attributes 里的集合改用 `ArrayList`，不再把 JDK 内部 `ImmutableCollections` 类型 id 写进 `oauth2_authorization`；授权行编解码器同时兼容升级前已写入的旧类型 id，已签发的 refresh token 无需重新登录。
- 快速开始示例的 `org_unit_id` 改为真实 UUID，`page` 从 1 开始。

## [1.0.0] - 未发布

首个正式版本发布时填写。
