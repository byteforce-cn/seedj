# Seedj1

Seedj1 是面向业务系统的 Java 21 / Spring Boot 多模块基础设施：契约层（`core`）零 Spring，
内存与 PostgreSQL 适配器并列，starter 负责装配，参考服务负责验证。

本仓库按 EIS 规范 v1.0.0 的 L1–L4 能力落地，并以可执行测试证明，而不是自述：

| 能力 | 交付 | 验证入口 |
|---|---|---|
| L1 数据契约 | 双时间轴对象模型/存储、UUIDv7、DDL、Seal/append、时点查询 | `core`、`seedj-infra-local`、`seedj-jdbc-postgresql` 契约测试 |
| L2 操作契约 | 幂等命令运行时、`/commands`、`/objects`、错误信封、`as_of`/`min_version` | `seedj-biz-starter`、`seedj-it` MockMvc/契约测试 |
| L3 授权与审计 | 审计哈希链 v1.0/v1.1、事件摘要复算、append-only、绑定/代理、`/resolve`、`/check`、SoD、属性 fail-closed | `core/audit`、`seedj-iam-starter`、`seedj-jdbc-postgresql` |
| L4 演进 | 兼容性门禁、迁移/回滚、弃用窗口、ADR 模板 | `-Pconformance`、`JdbcMigrationRollbackTest` |
| 活样板 | HR 员工 create/update、派生字段、时点读 | `seedj-server/hr`、`seedj-it/HrEmployeeSampleTest` |

## 构建

本地与 CI 使用同一套脚本（CI：`.github/workflows/ci.yml`）：

```bash
bash config/ci/verify.sh                      # 主门禁：全量测试 + 覆盖率 + 架构 + 文档链接 + 格式
bash config/ci/conformance.sh                 # 规范兼容性（需规范仓库，-Dspec.root=<path> 覆盖）
bash config/ci/release.sh                     # 发布门禁：SBOM + API 兼容性
```

脚本默认 `MAVEN_FLAGS=-o`（离线）；CI 设置 `MAVEN_FLAGS=""` 走在线仓库。
SpotBugs 在 M0–M3 处于 advisory（既有 Medium 发现待 T4.3 逐项修复/排除），脚本会打印
显式 WARN；发布 tag 的 `release-gates` job 已按阻断模式运行。

`-Pconformance` 默认从仓库相对位置解析规范仓库，可用 `-Dspec.root=<path>` 覆盖；
工具或工件缺席时构建显式失败，不静默跳过。

## 模块

- `core`：契约与纯 Java 语义，零 Spring、零 seedj 模块依赖。
- `seedj-infra-local`：内存实现（单测/本地演示）。
- `seedj-jdbc-postgresql`：PostgreSQL 实现、DDL/迁移/回滚。
- `seedj-biz-starter`：命令/查询/审计/事件总线/可观测性装配。
- `seedj-iam-starter`：OIDC/SCIM/RBAC/ABAC 与授权契约 API。
- `seedj-server/{iam,hr,im}`：可运行宿主。
- `seedj-it`：集成、契约对照与端到端测试。

## 合规边界与已知偏差

- 审计标识列保持 `TEXT` 而非规范 DDL 的 `UUID`：既有 `@Audited` 的 actor/resource 是业务字符串；
  哈希规范化仍与规范向量逐位一致。
- 授权绑定/代理标识沿用既有 IAM 字符串 ID；角色定义没有独立双时间轴，时点语义由绑定/代理承担。
- 条件声明通过 `IamPermissionCondition` bean 提供；manifest 自动解析留给后续扩展。
- HR 活样板覆盖 create/update、派生字段与时点读；组织/岗位/任职/审批激活未在样板中伪造。
- 派生密钥生产必须注入 `SEEDJ_FIELD_HASH_KEYS`；缺省值仅供测试。
- Outbox 死信状态为 `DEAD`；旧 `FAILED` 行仍可被运维方法识别。

## 文档

- 索引：[docs/README.md](docs/README.md)
- 设计：[契约一致性设计](docs/superpowers/specs/2026-11-05-contract-conformance-design.md)
- 决策：[ADR-0008 模块拓扑](docs/adr/ADR-0008-module-topology.md)
- 差距评估：[EIS 一致性差距](docs/assessment/2026-11-05-eis-conformance-gap.md)
- 分期验收：[conformance/runs](docs/conformance/runs/)
- 兼容规则/门禁：[config/conformance](config/conformance/README.md)
- 发布流程：[docs/releasing.md](docs/releasing.md)
- API 兼容性：[docs/api-compatibility.md](docs/api-compatibility.md)
