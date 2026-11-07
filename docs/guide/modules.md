# 模块与装配

## 依赖方向

```text
seedj-server/{hr,iam,im}  →  seedj-biz-starter / seedj-iam-starter
                          →  seedj-infra-local / seedj-jdbc-postgresql
                          →  core
```

`core` 不依赖 Spring，也不依赖任何适配器；适配器只实现 `core` 的 SPI，starter 负责把
实现装配起来，宿主只做组合。

## 模块职责

| 模块 | 职责 | 进发布物 |
|---|---|---|
| `core` | 契约：错误码、时间轴、命令、IAM、工作流、SPI、`@SeedjObject` 注解 | ✅ |
| `seedj-codegen` | 构建期注解处理器：schema、OpenAPI、迁移候选、可选读视图 | 构建期 |
| `seedj-infra-local` | 内存、文件、BouncyCastle 适配器 | ✅ |
| `seedj-workflow-local` | 工作流 SPI 的本地内存实现 | ✅ |
| `seedj-jdbc-postgresql` | PostgreSQL 适配器、DDL、版本化迁移、生产事件存储 | ✅ |
| `seedj-biz-starter` | 业务装配：本地开发认证、命令、查询、审计、事件总线 | ✅ |
| `seedj-iam-starter` | IAM 装配：OIDC、SCIM、RBAC、ABAC | ✅ |
| `seedj-server/hr`、`seedj-server/iam`、`seedj-server/im` | 可运行宿主（活样板） | ❌ |
| `seedj-it` | 契约对照、并发与端到端测试 | ❌ |

版本统一由 `bom` 管理；`config/public-api.txt` 是公共 API 的 allowlist，由门禁测试比对。

## 包命名

- 契约：`cn.byteforce.seedj.<cap>`（如 `cn.byteforce.seedj.command`）
- 实现：`cn.byteforce.seedj.infra.*`
- 装配：`cn.byteforce.seedj.starter.*`

新增能力时保持这个分层：SPI 与值类型进 `core`，实现进 `infra`/`jdbc`，`@AutoConfiguration`
进对应的 starter，宿主不出现业务装配代码。

## 启动一个宿主

三个宿主都是标准 Spring Boot 应用：

```bash
./mvnw -pl seedj-server/hr -am spring-boot:run -Dspring-boot.run.profiles=local
```

`local` 与 `postgres` 两个 profile 的差异见[配置说明](configuration.md)。宿主侧的活样板
（`HrEmployeeModule`）演示了「字段声明 + 命令 + 查询」三件事如何在一处收口。
