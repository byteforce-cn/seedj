# seedj1 × EIS Spec v1.0 差距评估

- 日期：2026-11-05
- 评估对象：`/home/wxy/idp/seedj1`（工作区快照；该目录不是 git 工作区）
- 参照规范：`/home/wxy/idp/bf/eis`（Spec v1.0.0，核心 2026-10-08 冻结；审计哈希链 v1.1）
- 方法：模块与依赖清单审查 + 规范工件逐条对照 + 关键实现静态阅读；基线实跑见 §0.2
- 判定口径：只认 EIS 侧可执行工件（`e2e-runner`、冻结向量、`contract-checker`、
  `compatibility-checker`、PG16 DDL 冒烟），不认自述

## 0. 结论

### 0.1 一句话

seedj1 目前**不是 EIS 实现**，而是一组质量不错的**可复用积木**（审计哈希链 v1.0、命令模板、
Outbox、时钟、IAM）。仓库内不存在任何 `eis-*` 模块，也没有消费 EIS 工件——
`grep -r "eis-"` 只命中审计/ID 的注释。要"开箱即用"，缺的是 **6 个内核/适配模块 + 一套 EIS
测试座**，不是若干补丁。

### 0.2 基线

| 命令 | 结果 |
|---|---|
| `mvn -o -B -DskipTests compile` | BUILD SUCCESS |
| `mvn -o -B test` | BUILD SUCCESS（本次运行；`seedj-it` 的 Docker IT 由 failsafe 另跑） |

### 0.3 合规级判定

| EIS 合规级 | 要求 | seedj1 现状 | 判定 |
|---|---|---|---|
| **L1** 数据契约 | 对象/事件/命令/视图元结构 + 双时间轴 + DDL 可在 PG16 执行 | 只有 `id` / `version` 两个元字段；无双时间轴；无业务对象表 | ❌ |
| **L2** 操作契约 | 命令/查询 API、幂等键、202/400/409、`as_of`、错误码 | 无业务 HTTP 命令面；`as_of` / `idempotency_key` 全仓 0 命中 | ❌ |
| **L3** 授权与审计 | `as_of` 授权解析 + SoD + 哈希链（含 `payload_digest`）+ 篡改检测 | 审计 v1.0 精确实现且冻结向量通过；授权模型与 04 契约不同构 | ❌（审计 v1.0 ✅） |
| **L4** 演进 | 兼容性门禁 + 迁移/回滚 + 弃用窗口 | 只有带 checksum 的迁移执行器 | ❌ |

结论：当前可声明 **L0**。审计是唯一"接近可直接交付"的一块。

---

## 1. "开箱即用"的判定口径

用一句话定义验收：

> 业务工程只添加一个 starter、用 EIS 扩展 manifest 声明对象/命令/权限，即自动获得 L1–L4
> 语义；EIS 仓库的工具链对生成物全绿。

可执行判据：

1. `tools/audit-verifier --vector` 的两个冻结向量（v1.0 / v1.1）在 Java 侧逐位一致；
2. `tools/contract-checker` 对 seedj 生成的 OpenAPI / AsyncAPI / Schema 全过；
3. `03` / `04` / `06` 契约用例有等价 Java 断言；
4. `tools/compatibility-checker --fail-on MAJOR` 进入 seedj CI；
5. EIS DDL（对象表 / 事件表 / 审计表）在 PostgreSQL 16 冒烟通过。

---

## 2. 现状盘点

| 模块 | 实际提供 | 与 EIS 的关系 |
|---|---|---|
| `core` | 契约层（零 Spring）：CQRS、audit、outbox、id、clock、security、workflow SPI | 审计/命令/时钟可复用；无 EIS 类型 |
| `seedj-infra-local` | 内存实现：EventStore、AuditStore、Lock、IAM、EventBus(file/memory)、Snowflake、PKI/Transit | `InMemoryEventStore` 是唯一事件存储 |
| `seedj-jdbc-postgresql` | PG 实现：audit、outbox、IAM、OAuth2、迁移执行器 | **无事件存储 / 无对象存储 / 无命令表 / 无授权契约表** |
| `seedj-biz-starter` | 资源服务器安全、`CommandAgent` 装配、审计装配、EventBus、可观测性 | 最接近"业务开箱即用"的装配层 |
| `seedj-iam-starter` | SAS OIDC、SCIM 2.0、RBAC、ABAC、TOTP、native-auth | 身份面完整；不是 EIS 授权契约实现 |
| `seedj-server/{iam,hr,im}` | 三个可运行宿主，`application.yml` 只有应用名 | 演示域在 test scope，不是 EIS 样板 |
| `seedj-it` | 集成测试聚合 + 夹具 | 无 EIS 用例 |

**关键事实**：`CommandAgent` 的事件存储只有 `InMemoryEventStore`（`grep "implements EventStore"`
仅命中该文件）。也就是说，即使按 seedj1 自己的事件溯源路线，生产可用的持久化写模型也尚未交付；
EIS 要求的对象表则完全没有。

---

## 3. 逐契约差距

### 3.1 数据契约（L1）

| EIS 要求 | seedj1 现状 | 判定 | 证据 |
|---|---|---|---|
| 对象元字段 10 项（`id/version/valid_from/valid_to/recorded_at/superseded_at/created_by/created_at/updated_by/updated_at`） | 实体基类只有 `id`；聚合根只有 `version` | ❌ | `domain/AbstractEntity.java`、`domain/AbstractAggregateRoot.java:40` |
| `id` = UUIDv7，DDL 为 `UUID` | `IdGenerator` 只保证唯一；实现为 Snowflake(`Long`) / String；无 UUIDv7 | ❌ | `id/IdGenerator.java`、`infra-local/id/*` |
| 对象表：`PK(id, recorded_at)`、`uq_<t>_current`、`ex_<t>_valid_overlap`、三个时点索引 | 无对象表 | ❌ | PG 模块无相关 DDL |
| 事件元结构（`occurred_at/recorded_at/actor/subjects/idempotency_key`） | `DomainEvent` 只有 `eventType()` + `schemaVersion()` | ❌ | `domain/event/DomainEvent.java` |
| 命令元结构（`issued_at/issuer/idempotency_key/status`） | `Command<ID>` 只有 `aggregateId()` | ❌ | `cqrs/Command.java` |
| 视图元结构（`as_of/recorded_as_of/computed_at/version`） | `AbstractProjection` 无时点字段 | ❌ | `cqrs/AbstractProjection.java` |
| Schema → DDL 由 `schema-compiler` 生成 | 无接入；DDL 手写在 Java 文本块中 | ❌ | 各 `*Migrations.java` |

### 3.2 操作契约（L2）

| EIS 要求 | seedj1 现状 | 判定 |
|---|---|---|
| `POST /commands/{commandType}`，必含 `idempotency_key`，202/400/409 | 无业务 Controller；演示 `HrEmployeeController` 返回 201 | ❌ |
| 重复提交返回原结果；内容不一致 → `EIS-409-001` | 无幂等键、无命令表、无指纹比对 | ❌ |
| `GET /commands/{id}` 查询命令终态 | 无 | ❌ |
| 查询支持 `as_of` / `recorded_as_of` / `min_version`；未追平 → 409 + `Retry-After` | 全仓 `as_of` 0 命中；无读模型水位 | ❌ |
| 命令响应只含 `locator` + `version`（ADR-0014），不得携带投影 | `CommandAgent.execute()` 只返回聚合 ID，不带版本 | ⚠️ 接近但缺版本 |
| 错误信封 `{code,message,details}`，码表 `EIS-{status}-{seq}` | `{error,message}`；内部枚举错误码 | ❌ |
| 事件通道 `events.{eventType}`，负载引用 `eis://core/event/v1` | Outbox topic 可承载，但事件信封不符合 `event.schema.json` | ❌ |

### 3.3 时间契约（L1/L2）

| EIS 规则 | 现状 |
|---|---|
| T-R001 四时间字段 | 无 |
| T-R002 半开区间 | 无 |
| T-R003 / T-R004 区间约束 | 无 |
| T-R005 单当前记录（唯一索引） | 无 |
| T-R006 系统时间 append-only + 封口 | 无 |
| T-R007 UTC 微秒 | ✅ `Clock` / `Clocks` 已强制微秒 UTC |
| T-R008 `as_of` 单版本 | 无 |
| T-R009 有效区间不重叠（四维排他） | 无 |
| T-R010 `recorded_at >= occurred_at` | 无约束 |
| T-R011 迟到事实追加 | 无 |

唯一可直接复用的是时钟；其余 10 条全部缺失。

### 3.4 授权契约（L3）

| EIS 要求 | seedj1 现状 | 判定 |
|---|---|---|
| `principal` / `role` / `binding` / `proxy` 四类对象，绑定带 `scope` + 双时间轴 | IAM 有 user/group/role/permission；`iam_user_role` 无 `scope`、无 `valid_from/valid_to`、无 `superseded_at`；无 proxy | ❌ |
| `POST /resolve`（主体 + `as_of` → 角色/权限 + `sod_warnings`） | 无；`IamAuthorizationService.effective()` 取当前有效权限（TTL 缓存） | ❌ |
| `POST /check`（主体 + 权限 + `as_of` → `allowed` + `reason_code`） | 有 PDP，但输入是资源属性 ABAC，无 `as_of`、无 `reason_code` | ❌ |
| SoD prevent/warn（`SOD-001..003` 语义） | 0 | ❌ |
| 属性快照 API + ATR-001..009（属主、`as_of` 一致、fail-closed、条件 DSL） | 无；ABAC 条件读调用方提供的 `resourceAttributes` | ❌ |

已有且可复用的部分：OIDC/JWT 身份、`Caller`（roles/permissions/attributes）、RBAC 角色继承、
ABAC（deny wins、优先级）、SCIM。**结论：认证面可复用，授权契约面需新建。**

### 3.5 审计契约（L3）

**已满足（v1.0）：**

- 九元规范化与 `hash-chain.spec.md §2` 逐字一致；
- 创世冻结向量哈希逐位一致（`CanonicalHasherTest:52`）；
- 序号 + 链头在同一事务内串行分配（`AuditStore.nextSequenceAndPrevHash`），提交顺序连续；
- 验证器覆盖 `sequence_gap` / `prev_hash_mismatch` / `invalid_hash` / `empty_chain`；
- 已有链头锚定接口与 `audit_head` 表。

**差距：**

| 差距 | 说明 | 证据 |
|---|---|---|
| v1.1 `payload_digest` 缺失 | `AuditEntry` 无该字段；`AuditAppendRequest` 无 `event_ids`；无事件导出复算 | `audit/AuditEntry.java`、`audit/AuditAppendRequest.java` |
| 验证器错误类型不全 | 无 `payload_digest_mismatch` / `payload_digest_missing_event` | `audit/AuditVerifier.java` |
| DDL 不对齐 | 表列为 `occurred_at`，`id/actor` 为 `VARCHAR`；无 `payload_digest` 列、无 `CHECK`、无 append-only `RULE` | `jdbc/audit/AuditMigrations.java` |
| 审计覆盖不全 | `AnnotationCommandAudit` 只审计带 `@Audited` 的命令；payload 只有 `{command, aggregateId}`，不含事件 ID | `audit/wiring/AnnotationCommandAudit.java` |
| 外锚定未实现 | 只有 `AuditAnchor` 接口与 `anchorNow()`；无按期签名/发布 | `audit/AuditAnchor.java`、`AuditChain.anchorNow()` |

### 3.6 演进契约（L4）

| EIS 要求 | seedj1 现状 | 判定 |
|---|---|---|
| `compatibility-checker --fail-on MAJOR` 门禁 | 无 | ❌ |
| 迁移 + 回滚模板与演练 | `JdbcMigrationRunner` + checksum 可作底座；无回滚 | ⚠️ |
| 弃用窗口 ≥2 MINOR 且 ≥90 天 | 无 | ❌ |
| ADR 与契约变更绑定 | 无 | ❌ |
| OpenAPI/AsyncAPI 工件可被 `contract-checker` 校验 | 无 | ❌ |

### 3.7 横切缺口

| 项 | 现状 |
|---|---|
| 写入专用派生字段（DRV-001..005，`writeOnly`） | 0 命中；`Transit` 是通用 HMAC，不满足 DRV-002/005 |
| 多租户 | seedj 全域 `tenantId`；EIS 核心对象无 tenant（规范已知边界）→ 需要明确"扩展字段 / 部署维度"策略 |
| 错误码体系 | seedj 内部枚举 + `{error,message}`；需 EIS `EIS-{status}-{seq}` 映射层 |
| EIS CI | 无；EIS 工具是 Python（PyYAML/jsonschema），需决定集成方式 |
| 合规声明 | 无 |

---

## 4. 可复用资产（不要重写）

| 资产 | 可服务于 | 注意 |
|---|---|---|
| `core/audit`（CanonicalHasher / AuditChain / AuditVerifier） | 05 审计契约 | 升 v1.1 即可；向量测试已就绪 |
| `core/cqrs/CommandAgent` | 02 命令运行时骨架 | 顺序（锁→事务→保存→发事件→审计）与 kernel 接近；需"对象表写入"变体 |
| `core/outbox` + PG 实现 | `extensions/outbox` | 分区顺序、租约、`FOR UPDATE SKIP LOCKED` 已具备；列名/状态/`dead`/Inbox 需对齐 |
| `core/clock` | T-R007 / T-R010 | 微秒 UTC + HLC 可直接用 |
| `core/Migration` + `JdbcMigrationRunner` | L4 迁移底座 | 有 checksum；需补回滚与兼容性门禁 |
| `seedj-iam-starter` | 认证与身份管理 | 不是 04 授权契约；binding 模型需扩展 |
| `core/ErrorDescriptor/ErrorCategory` | 错误码映射 | 与 EIS 码表做映射，不替换 |

---

## 5. 关键决策（需 ADR）

**D1（最大分叉）写入模型：事件溯源 vs 双时间轴对象表。**
EIS L1 要求的是"对象表 + 追加版本 + 唯一当前记录 + 排他约束"，seedj1 是"事件溯源聚合 + 版本 CAS"。
两条路：

- **A（推荐）**：新增 EIS 对象存储作为权威写模型；命令 → 封口旧行 + 追加新行 + 审计 + outbox
  同一事务；事件作为 outbox/事件表副产品。事件溯源保留为可选 profile。
- **B**：事件溯源为主，对象表作为投影。需要保证事件追加与版本封口同事务，且 `recorded_at`
  语义可重放，复杂度显著更高。

**D2 授权权威由谁提供。** EIS 04 要求有服务实现 `/resolve` `/check`。建议：判定逻辑放
`seedj-eis-authz`，权威数据由 IAM 存储扩展（新增 binding/proxy 表），而不是另起一套身份库。

**D3 ID 策略。** EIS 冻结 UUID。建议新增 UUIDv7 生成器并统一审计/对象/事件 ID；
现有 Snowflake 保留给非 EIS 场景，`IdCodec` 继续负责边界转换。

**D4 租户。** 建议 tenant 作为扩展字段或部署维度，不进入 EIS 核心对象字段；在
`seedj-eis-core` 里作为"附加列"处理，避免污染冻结元结构。

**D5 规范消费方式。** CI 装 EIS Python 工具（最完整）或消费 `@byteforce/eis-spec` npm 包
（只有 `manifest|verify`）。`compatibility-checker` 目前只有 Python 实现，需决定移植还是装环境。

**D6 审计 v1.1 升级。** `AuditEntry<ID>` 泛型已允许扩展，但 DDL 必须加列并保持旧条目可验证
（v1.0 公式在 `payload_digest` 缺失时不变）。

---

## 6. 目标形态

```
seedj-eis-core       双时间轴对象存储 + 命令运行时（幂等/规则/版本封口）+ DRV 派生字段
seedj-eis-audit      哈希链 v1.1 + 事件摘要 + 锚定 + 与 verify.tool 等价 CLI
seedj-eis-authz      /resolve /check 客户端 + 本地 PDP + SoD + 属性快照
seedj-eis-temporal   as_of / recorded_as_of SQL 模板 + JdbcTemplate / MyBatis 适配
seedj-eis-web        POST /commands/{type}、GET /commands/{id}、GET /objects/...、EIS 错误信封
seedj-eis-test       冻结向量、契约 TCK、EIS 用例移植、e2e 夹具
seedj-eis-starter    聚合 + @EnableEis + 自动配置（一个依赖、一个注解）
```

配套：

- `seedj-server/hr` 改成 EIS 风格活样板（对象表 + 命令 + `as_of` + 审计），作为"开箱即用"的
  可运行证据；
- `seedj-it` 增加 EIS 对照用例（把 `03/04/06` 的关键断言移植为 Java 测试）。

---

## 7. 差距清单（按优先级）

| ID | 缺口 | 契约 / 等级 | 阻断 | 工作量 | 验收 |
|---|---|---|---|---|---|
| G1 | 双时间轴对象存储（封口/追加/唯一当前/排他约束） | 01 / 03 · L1 | 是 | L | PG16 上 T-R001..011 断言全过 |
| G2 | `as_of` / `recorded_as_of` 查询与 `min_version` 409 | 02 / 03 · L2 | 是 | M | `03` 用例等价断言 + `Retry-After` |
| G3 | 命令 HTTP 面 + 幂等表 + 命令状态 + 202/409 + locator/version | 02 · L2 | 是 | L | `contract-checker` 对生成 OpenAPI 全过 |
| G4 | EIS 错误码目录与信封 `{code,message,details}` | 02 · L2 | 是 | S | 错误路径用例覆盖 400/401/403/404/409/500/503 |
| G5 | UUIDv7 + 对象/事件/审计 ID 统一 | 01 / 05 · L1 | 是 | S | 格式与排序断言 |
| G6 | 审计 v1.1（`payload_digest` + 事件复算 + 错误类型） | 05 · L3 | 是 | M | 两个冻结向量 + 篡改用例 |
| G7 | 审计 DDL 对齐（列/类型/CHECK/append-only/`audit_head`） | 05 · L1 | 是 | S | EIS `audit.ddl` 对照 + PG 冒烟 |
| G8 | 授权对象模型（binding `scope`/双时间轴 + proxy） | 04 · L3 | 是 | L | `04` 契约 tests 等价断言 |
| G9 | `/resolve` `/check`（`as_of` + `reason_code` + `sod_warnings`） | 04 · L3 | 是 | L | `authz-resolver --self-test` 等价 |
| G10 | SoD prevent/warn | 04 · L3 | 是 | M | SOD-001..003 用例 |
| G11 | 属性快照 + ATR-001..009 | 04 · L3 | 否 | L | 快照生命周期用例 |
| G12 | 兼容性门禁 + 迁移/回滚模板 + 弃用窗口 | 06 · L4 | 否 | M | `compatibility-checker --fail-on MAJOR` |
| G13 | DRV writeOnly 派生字段（密钥环 + 投影剔除） | 01 · L1 | 否 | M | DRV-001..005 用例 |
| G14 | Outbox/Inbox 对齐（`dead`/`aggregate`/Inbox 落库） | extensions/outbox | 否 | M | OBX-001..012 用例 |
| G15 | EIS 合规声明 + CI 挂钩 + 活样板 | 全 · 治理 | 否 | S | `e2e-runner` + 构建绿 |

建议顺序：**G1→G3→G4→G5→G6→G7**（先拿到 L1+L2 的可演示纵切），再 **G8→G9→G10**（L3），
最后 G11–G15。

---

## 8. 验收挂钩

| 缺口 | EIS 侧证明 | seedj 侧证明 |
|---|---|---|
| G1 / G2 | `schema-compiler check` + `03` scenario 用例 + PG DDL 冒烟 | PG16 Testcontainers：区间/封口/单当前/排他断言 |
| G3 / G4 | `contract-checker` + `02` 用例 | MockMvc：202/400/409、幂等重放、`GET /commands/{id}` |
| G6 / G7 | `audit-verifier --vector` + 篡改夹具 | Java 跑同一向量 + 链校验 + append-only 拒绝 |
| G8 / G9 / G10 | `authz-resolver --self-test` + `04` 用例 | 等价 Java 用例（时点/代理/SoD） |
| G12 | `compatibility-checker --fail-on MAJOR` + `06` 用例 | CI 门禁 + 迁移/回滚演练 |
| G14 | `tools/relay --self-test` + OBX 用例 | relay 集成测试（顺序屏障/租约/死信） |

---

## 9. 风险

1. **双写模型不一致**：对象表与事件流若不在同一事务封口，`recorded_at` 语义会漂移；D1 必须
   先定，否则 G1/G3 会返工。
2. **冻结契约不可改**：核心元结构只能适配，不能"让 EIS 迁就 seedj"；所有差异必须落在
   seedj 侧或扩展层。
3. **IAM 表结构迁移**：现有 `iam_user_role` 无 scope/时间轴，新增 binding 表需要数据迁移与
   双写过渡，影响已部署 IAM。
4. **审计串行写吞吐**：`nextSequenceAndPrevHash` 在事务内持锁，是单链吞吐上限；EIS 语义要求
   连续序列，属于规范约束而非实现缺陷，但部署侧需要评估。
5. **工具链语言**：EIS 的校验工具是 Python，Java CI 需装 PyYAML/jsonschema 或移植核心算法。
6. **`as_of` 查询性能**：四维排他约束 + 双时间轴索引是必须项，不要用应用层过滤替代。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 无 `eis-*` 模块 | `grep -r "eis-"` 仅命中 `audit/AuditEntry.java:14`、`audit/AuditAppendRequest.java:9`、`core/utils/CanonicalJsonHelper.java:28`、`cqrs/IdCodecs.java:10` |
| 无 `as_of` / `recorded_as_of` / `valid_from` / `idempotency_key` | 全仓 Java/SQL 检索 0 命中 |
| 唯一事件存储为内存实现 | `grep "implements EventStore"` → `infra-local/cqrs/memory/InMemoryEventStore.java` |
| 聚合根只有 `version` | `core/domain/AbstractAggregateRoot.java:40` |
| 命令只有聚合 ID | `core/cqrs/Command.java` |
| 审计 v1.0 向量通过 | `core/src/test/java/.../CanonicalHasherTest.java:52`；`mvn -o -B test` 绿 |
| 审计无 `payload_digest` | `core/audit/AuditEntry.java`、`core/audit/AuditAppendRequest.java` |
| 审计 DDL 不对齐 | `seedj-jdbc-postgresql/.../audit/jdbc/AuditMigrations.java`（`occurred_at`、无 CHECK/RULE） |
| 命令审计只覆盖 `@Audited` | `seedj-biz-starter/.../audit/wiring/AnnotationCommandAudit.java` |
| 演示命令端点返回 201 | `seedj-biz-starter/src/test/.../demo/hr/web/HrEmployeeController.java` |
| 401/403 信封 | `seedj-biz-starter/.../biz/security/JsonAccessDeniedHandler.java:39` |
| IAM 绑定无 scope/时间轴 | `seedj-jdbc-postgresql/.../iam/jdbc/IamMigrations.java`（`iam_user_role`） |
| Outbox 状态为 PENDING/PROCESSING/SENT/FAILED | `seedj-jdbc-postgresql/.../outbox/jdbc/PostgresOutboxStore.java` |
| 无 JDBC 锁实现 | `seedj-infra-local` 仅有 `lock/memory/InMemoryLockProvider.java` |
| 迁移执行器 | `seedj-jdbc-postgresql/.../jdbc/JdbcMigrationRunner.java`、`core/MigrationChecksums.java` |

## 附录 B：本评估未覆盖

- 运行时性能与容量（未做压测）；
- 已部署环境的 IAM 数据迁移成本（需要实际数据分布）；
- `extensions/{accounting,inventory,approval,hr}` 的 Java 域实现映射（属 server 侧，非通用内核）；
- EIS `cases/procurement` 端到端场景的 Java 复刻（建议放到 G15 之后）。
