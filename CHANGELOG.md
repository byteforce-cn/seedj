# 变更记录

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- `seedj-im-starter`（阶段 1）：帧协议、Netty TCP/WebSocket 传输、IAM JWT 握手、心跳与未实现命令 `501`；`seedj-server/im` 改为单类宿主。
- 开发者文档站（`docs/`，VitePress）与 GitHub Pages 发布。
- 贡献指南、安全政策、行为准则、支持说明与 issue/PR 模板。

### Changed

- README 改为公开仓门面：可复制的快速开始、业务改动示例与模块表。
- 源码树中的文档引用全部指向仓内 `docs/`；架构决策不再出现在公开树。

### Fixed

- `search` 的当前视图快路径补上 `valid_to IS NULL`，与 `find` 共用同一口径：关闭业务时间轴（软删/归档）的对象不再在列表里复活，`find` 与 `search` 结论一致；`uq_*_current` 仍只约束未封口行，重新打开通过先封口再追加实现。
- IAM `store=jdbc` 的 refresh_token 交换不再 500：claims/attributes 里的集合改用 `ArrayList`，不再把 JDK 内部 `ImmutableCollections` 类型 id 写进 `oauth2_authorization`；授权行编解码器同时兼容升级前已写入的旧类型 id，已签发的 refresh token 无需重新登录。
- 快速开始示例的 `org_unit_id` 改为真实 UUID，`page` 从 1 开始。

## [1.0.0] - 未发布

首个正式版本发布时填写。
