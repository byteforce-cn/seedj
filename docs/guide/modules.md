# 模块与装配

## 依赖方向

```text
seedj-server/{hr,iam,im}  →  seedj-biz-starter / seedj-iam-starter
seedj-iam-starter         →  seedj-iam-engine  →  seedj-iam-contract
seedj-server/im           →  seedj-im-starter  →  seedj-biz-starter
                          →  seedj-infra-local / seedj-jdbc-postgresql
                          →  seedj-infra-coord  →  coord-java-sdk
                          →  core
seedj-server/ai           →  seedj-ai-starter  →  seedj-biz-starter
                          →  seedj-infra-local / seedj-jdbc-postgresql
                          →  seedj-{workflow,transit,ai,im,iam}-contract  →  core
```

能力契约模块只依赖 `core`（`workflow-contract` 连 `core` 都不依赖），彼此不互相依赖；
`core` 不依赖 Spring，也不依赖任何适配器；适配器只实现 `core` 的 SPI，并自带
`@AutoConfiguration` 用 `seedj.*.provider` 竞争（starter 不再编译期依赖适配器），宿主只做组合。

`seedj-iam-engine` 是不依赖 Spring/JDBC 的纯逻辑制品（身份/授权判定、TOTP/MFA、SCIM 用例），
由 starter 装配、适配器提供存储 SPI 实现；这条边界由 ArchUnit 的 “IAM 引擎不依赖 Spring/JDBC”
规则守着。

AI 这一支的单向性由 ArchUnit 规则守着：`seedj-ai-starter` 依赖 `seedj-biz-starter`（拿
`CallerProvider`、`CommandCatalog` 与命令入口线格式），**`seedj-biz-starter` 不得反向依赖
`seedj-ai-starter`**，`cn.byteforce.seedj.ai` 也不得依赖 Spring 或 JDBC。

## 模块职责

| 模块 | 职责 | 进发布物 |
|---|---|---|
| `core` | 内核契约：错误码、时间轴、命令、读模型、安全、SPI、`@SeedjObject` 注解 | ✅ |
| `seedj-workflow-contract` | 工作流契约（零外部依赖） | ✅ |
| `seedj-transit-contract` | 传输/加密契约 | ✅ |
| `seedj-pki-contract` | PKI 契约：证书/CSR、Transit 签名、吊销与 CRL、alias 映射、TLS 配置 | ✅ |
| `seedj-ai-contract` | AI 契约：命令指纹、动作分类、策略与账本值类型 | ✅ |
| `seedj-im-contract` | IM 契约：会话、成员、消息与存储端口 | ✅ |
| `seedj-iam-contract` | IAM 契约：用户/组/角色/策略、SCIM 与授权值类型 | ✅ |
| `seedj-iam-engine` | IAM 纯逻辑：身份/授权判定、TOTP/MFA、SCIM 用例（无 Spring/JDBC） | ✅ |
| `seedj-codegen` | 构建期注解处理器：schema、OpenAPI、迁移候选、可选读视图 | 构建期 |
| `seedj-infra-local` | 内存、文件、BouncyCastle 适配器 | ✅ |
| `seedj-workflow-local` | 工作流 SPI 的本地内存实现 | ✅ |
| `seedj-jdbc-postgresql` | PostgreSQL 适配器、DDL、版本化迁移、生产事件存储 | ✅ |
| `seedj-infra-coord` | coord 适配器（组合 coord 原语）：ID、编号、全局序列、EventBus 发布/消费、分布式锁、PKI/Transit | ✅ |
| `seedj-biz-starter` | 业务装配：本地开发认证、命令、查询、审计、事件总线 | ✅ |
| `seedj-im-starter` | IM 长连接装配：帧协议、Netty TCP/WebSocket、JWT 握手、连接注册表 | ✅ |
| `seedj-iam-starter` | IAM 装配：OIDC、SCIM、RBAC、ABAC | ✅ |
| `seedj-ai-starter` | AI 装配：决策账本、DecisionToken、guard、决策面与有界循环（[指南](ai-starter.md)） | ✅ |
| `seedj-server/hr`、`seedj-server/iam`、`seedj-server/im`、`seedj-server/ai` | 可运行宿主（活样板） | ❌ |
| `seedj-it` | 契约对照、并发与端到端测试 | ❌ |

版本统一由 `bom` 管理；`config/public-api.txt` 是公共 API 的 allowlist，由门禁测试比对。

## 包命名

- 契约：`cn.byteforce.seedj.<cap>`（如 `cn.byteforce.seedj.command`）
- 实现：`cn.byteforce.seedj.infra.*`
- 装配：`cn.byteforce.seedj.starter.*`

新增能力时保持这个分层：SPI 与值类型进 `core`，实现与“选哪个 provider”的
`@AutoConfiguration` 进 `infra`/`jdbc`；starter 只装配能力（运行时、HTTP 面、策略），
宿主不出现业务装配代码。

## 启动一个宿主

四个宿主都是标准 Spring Boot 应用：

```bash
./mvnw -pl seedj-server/hr -am spring-boot:run -Dspring-boot.run.profiles=local
```

`local` 与 `postgres` 两个 profile 的差异见[配置说明](configuration.md)。宿主侧的活样板
（`HrEmployeeModule`）演示了「字段声明 + 命令 + 查询」三件事如何在一处收口。
