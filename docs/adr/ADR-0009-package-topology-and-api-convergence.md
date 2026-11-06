# ADR-0009：架构整改的包拓扑与 API 收敛（1.0.0 前破坏性变更）

- 状态：已接受
- 日期：2026-11-06
- 决策者：seedj 架构整改
- 关联：`docs/adr/ADR-0008-module-topology.md`、`docs/conformance/change-list-2026-11-06-architecture-remediation.md`

## 背景

1.0.0 尚未发布，仓库里同时存在三类结构债：

- `core` 契约模块里混入 PostgreSQL 时钟适配器与 `schema.sql`，使契约模块依赖 JDBC；
- 多个包同时出现在契约模块与实现模块（`id`、`pki`、`transit`、`iam.authz`、`iam.scim`，以及
  实现侧大量 `cn.byteforce.seedj.<cap>.*`），JPMS/OSGi/类路径扫描都无法确定性解析；
- 异常体系有 `BaseException` / `BizException` / `RemoteException` 与后来的
  `ContractException` / `InfrastructureException` 两套，命令运行时还保留 8 参兼容构造器。

这些都属于“发布后很难修”的破坏性变更，必须在 1.0.0 前一次还清。

## 决策

### 1. 包命名与依赖方向

| 层 | 包 | 模块 |
|---|---|---|
| 契约 | `cn.byteforce.seedj.<cap>` | `core` |
| 适配器 | `cn.byteforce.seedj.infra.local.<cap>` / `cn.byteforce.seedj.infra.jdbc.<cap>` | `seedj-infra-local` / `seedj-jdbc-postgresql` |
| 装配 / Web | `cn.byteforce.seedj.starter.<area>` | `seedj-biz-starter` / `seedj-iam-starter` |
| 宿主 | `cn.byteforce.seedj.server.<name>` | `seedj-server/*` |

- `core` 不得出现 `java.sql` / `javax.sql` / Spring 依赖；PG 时钟适配器与 DDL 资源移入
  `seedj-jdbc-postgresql`。
- 同一个 Java 包只能属于一个 Maven 模块；`SplitPackageTest` 的允许清单在整改后为空。
- `iam` 根包持有 policy / SCIM AST 与主体模型；`iam.policy` 只保留求值器；
  `iam.scim` 只依赖 `iam` + `iam.spi`，不得被 `iam` 反向依赖。

### 2. API 收敛

- 删除 `BaseException`、`BizException`、`RemoteException`；契约错误统一
  `ContractException` + `ContractErrorCode`，技术错误统一 `InfrastructureException`。
- 删除 `CommandRuntime.Options` 的 8 参兼容构造器；只保留 9 参全参与 `forTests`。
- `HrEmployeeCreate.NONE` 已不存在，不再恢复。
- `config/public-api.txt` 按删除/移动后的实际表面重新生成。

## 兼容性分析（契约变更必填）

- 兼容规则：`config/conformance/compatibility.rules.yaml` → 无条目（本次不改
  `/commands`、`/objects` 线格式，也不改 Schema/OpenAPI/AsyncAPI 的必填字段或语义）
- 判定级别：MAJOR（仅对 Java API 消费者：包名与类型删除）
- `requires` 完成情况：
  - [x] migration_guide：本 ADR 与变更清单给出旧包 → 新包映射
  - [x] consumer_migration：仓库内调用方已全部迁移，`mvn verify` 通过
  - [ ] migration / rollback / dual_write / backfill：不涉及数据迁移
  - [ ] deprecation_window：1.0.0 未发布，无外部消费方，按变更清单记录豁免

## 后果

- 正面：契约模块零数据库依赖；split package 与包环门禁转绿；异常与命令装配只有一个入口。
- 负面：所有 Java 调用方的 import 需要迁移；`config/public-api.txt` 出现一次大 diff；
  包移动使部分文件因显式 import 增加而变长（`BcTransitProvider` 611 行，已登记并由
  T3.5 处理）。
- 后续：`docs/**` 的旧包名在 T4.1 统一重写；SpotBugs 历史发现留在 T4.3 收口。

## 备选方案

- 保留 split package、只加 ArchUnit 白名单：白名单会永久存在，且类路径解析仍不确定。
- 保留 `BaseException` 兼容层：没有外部消费者，兼容层只会继续吸引新代码。
- 把 PG 时钟实现留在 `core` 并用 `provided` 依赖打补丁：契约模块仍会感知 SQL 类型。
