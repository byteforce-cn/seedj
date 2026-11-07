# FAQ

## 能直接用于生产吗？

基础设施部分可以：命令运行时、双时间轴存储、PostgreSQL 迁移与审计链都有集成测试。
但先读[已知限制](guide/known-limitations.md)——单租户、事件存储只在 PostgreSQL、IAM 尚未
宣称生产可用。

## 和 EIS 规范是什么关系？

Seedj 实现 [EIS](https://github.com/byteforce-cn/eis) 规范的数据、命令、查询与授权契约；
`config/conformance/` 下保存规范契约的副本与 baseline，`check-spec-alignment.sh` 逐字节校验
副本与规范一致。业务代码不依赖规范仓库，构建也不依赖它。

## 支持哪些数据库？

目前只有 PostgreSQL（`seedj-jdbc-postgresql`）。本地开发用内存实现，不需要数据库。
新增适配器的方式见[基础设施 SPI](guide/spi.md)。

## 必须用 Spring Boot 吗？

`core` 零 Spring 依赖，可以用 `CommandHarness` 直接测业务规则。只有 starter 与宿主需要
Spring Boot；如果你自己装配，实现 `core` 的 SPI 即可。

## 怎么加字段/命令/查询？

看[业务开发手册](guide/cookbook.md)。字段只在 `@SeedjObject` record 上声明一次，命令与查询
在模块声明里收口。

## 怎么报告安全问题？

不要开公开 issue，按 [SECURITY.md](https://github.com/byteforce-cn/seedj/blob/main/SECURITY.md)
走私密报告。

## 为什么没有 Maven Central 坐标？

1.0.0 尚未发布。发布后会在 README 补上坐标；在那之前请从源码构建。
