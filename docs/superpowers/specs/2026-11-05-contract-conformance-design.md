# seedj1 契约合规落地设计（L1–L4）

- 状态：待批准
- 日期：2026-11-05
- 输入：`docs/assessment/2026-11-05-eis-conformance-gap.md`（差距评估，G1–G15）、
  `docs/adr/ADR-0008-module-topology.md`（模块拓扑）、规范仓库 `/home/wxy/idp/bf/eis`（Spec v1.0.0）
- 约束来源：本设计会话中确认的三条硬规则（见 §2）

---

## 1. 意图

seedj1 现在是一组质量不错但彼此独立的积木：审计哈希链 v1.0、命令代理、Outbox、时钟、IAM。
差距评估的结论是 **L0**——它不是规范的实现，而是实现规范可用的材料。

本设计要把规范的 L1–L4 语义**变成 seedj 的一等能力**：业务侧加一个 starter、写一个命令、
一个对象 Schema，就自动获得双时间轴、幂等命令、`as_of` 读、审计链、时点授权与演进门禁。

为什么现在做：规范侧已经有可执行工件（`tools/e2e-runner`、冻结向量、`contract-checker`、
`compatibility-checker`、PG16 DDL 冒烟），合规可以被机器判定，不再依赖自述。

---

## 2. 硬约束

| # | 约束 | 说明 |
|---|---|---|
| C1 | 代码里不得出现规范名（`eis`） | 含 Java 包名/类名/字段名、SQL 表名/列名/约束名、配置键、模块名、脚本名、资源文件名 |
| C2 | 不新增 Maven module，不新增仓库根目录 | 新代码只能进 `core`、`seedj-infra-local`、`seedj-jdbc-postgresql`、`seedj-biz-starter`、`seedj-iam-starter`、`seedj-server/*`、`seedj-it`，以及既有的 `bom`、`config`、`docs` |
| C3 | 规范工件不可改 | 差异只能落在 seedj 侧；不得为了让 seedj 好写而调整冻结契约 |
| C4 | 依赖方向沿用 ADR-0008 | `core` 零 Spring、零 seedj 依赖；两个实现模块互不依赖；两个 starter 互不依赖；`seedj-server-hr` 不得带 `seedj-iam-starter` |

### 2.1 C1 的线格式例外（已确认）

以下三类是规范的**线格式标识符**，改名会让跨系统互操作与工具对照失效，保留原样：

1. 错误码 `EIS-{status}-{seq}`（`02-operation-contract/error-codes.yaml` 冻结，已由你确认为例外）；
2. JSON Schema 的 `$id` / `$ref` URI：`eis://core/{object,command,event,view,audit}/v1`；
3. 规范生成 DDL 的域类型：`eis_id` / `eis_version` / `eis_timestamp`。

**不在例外内、必须改名的**：

| 规范里的名字 | seedj 侧名字 | 理由 |
|---|---|---|
| `EIS_FIELD_HASH_KEYS`（DRV-002 参考实现键名） | `SEEDJ_FIELD_HASH_KEYS` | 只是参考实现键名，不是冻结契约 |
| 派生 scope 前缀 `eis:<ext>:<obj>:<field>:v1`（DRV-003） | `seedj:<ext>:<obj>:<field>:v1` | scope 是应用数据，无冻结向量依赖；域分离语义不变 |
| `eis-*` / `starter-eis-*` 模块名 | 不适用（不新增模块） | C2 |

### 2.2 C2 的解释（本设计的默认口径）

- 禁止：新增 Maven module、新增仓库根目录（如 `seedj-contract/`）。
- 允许：在既有模块内新增 package；package 根优先复用既有能力名（`domain`、`cqrs`、`audit`、
  `iam`、`security`、`id`、`outbox`、`jdbc`、`biz`、`web`、`wiring`、`memory`）。
- 允许：在 `docs/` 下新增 `docs/conformance/`（规范工件与运行记录），在 `config/` 下新增
  `config/conformance/`（门禁配置）。二者都不是模块。

---

## 3. 非目标与反目标

**非目标**

- 不实现 `extensions/{accounting,inventory,approval,procurement}` 的业务域；只做 `hr` 活样板。
- 不提供浏览器读面；通用查询 API 只作内部/服务面（ADR-0006）。
- 不替换既有事件溯源 `CommandAgent` 与其 `EventSourcedRepository`，两条写入模型并存。
- 不做已部署 IAM 数据的大规模迁移；新授权表只做**可选**回填。

**反目标**（即使“能跑”也算失败）

- 翻译错误码（如把 `EIS-409-003` 改成 `SEEDJ-...`）：会直接失去与规范工具对照的资格。
- 新增模块或把语义拆到多个新 jar：违反 C2，且让依赖方向不可读。
- 让规范适配 seedj：任何差异必须落在 seedj 侧或扩展层。
- 用应用层过滤代替数据库约束（区间重叠、单当前记录、`min_version` 都是 DB 事实）。
- 把 Python 工具做成运行时或必需构建依赖：CI 可选，测试用黄金文件回放。

---

## 4. 方案

| 方案 | 结论 | 理由 |
|---|---|---|
| **A. 内核移植 + 薄适配** | **采用** | 规范的参考内核（`tools/lib/kernel.py`，1444 行）是可执行规格。把语义移植成 `core` 的纯 Java，存储/Web/装配只是适配器，15 个缺口共用一套语义，不会在 PG/内存/Web 三处分叉 |
| B. 契约生成优先 | 弃用 | 结构一致性最强，但引入 Python 构建依赖，DDL 仍需二次改名，而排序、拒绝码、`as_of` 边界这些最难的部分仍要另写 |
| C. 按缺口并行铺开 | 弃用 | 起步快，但封口、`as_of`、审计顺序会三处走样；评估文档把“双写模型不一致”列为第一风险，返工概率最高 |

方案 A 的分层：

```
core（纯 Java 语义：规则 + 数据模型 + SPI 契约）
  ├── seedj-jdbc-postgresql（SQL / 事务 / 约束 / 迁移）
  ├── seedj-infra-local（内存实现，供单测与本地演示）
  └── starter 层（装配 + HTTP 面）
        ├── seedj-biz-starter（命令面、对象查询、错误信封、审计/追踪）
        └── seedj-iam-starter（/resolve、/check、绑定与代理、属性快照）
              └── seedj-server/{iam,hr}（可运行宿主）
                    └── seedj-it（契约对照与端到端）
```

---

## 5. 词汇与命名

规范概念 → seedj 名称（Java）→ 落点。**列出的名字都不含 `eis`。**

| 规范概念 | seedj 名称 | 位置 |
|---|---|---|
| 业务对象元结构（10 字段） | `ObjectVersion`（record） | `core/domain` |
| 双时间轴规则 T-R001..011 | `TemporalRules` | `core/domain` |
| 封口/追加 | `Sealing`（seal + append） | `core/domain` |
| 时点查询参数（as_of / recorded_as_of / min_version） | `TemporalQuery` | `core/domain` |
| 对象存储 SPI | `TemporalStore` | `core/domain` |
| 事件信封（occurred_at/recorded_at/actor/subjects/idempotency_key/version） | `EventEnvelope` | `core/domain/event` |
| UUIDv7 | `Uuid7IdGenerator` | `seedj-infra-local/id` |
| 命令记录（含幂等与终态） | `CommandRecord` | `core/cqrs` |
| 命令受理结果（locator + version） | `CommandReceipt` | `core/cqrs` |
| 幂等指纹 | `IdempotencyFingerprint` | `core/cqrs` |
| 命令存储 SPI | `CommandStore` | `core/cqrs/spi` |
| 对象写入命令代理 | `TemporalCommandAgent` | `core/cqrs` |
| 错误码目录（线格式保留 `EIS-`） | `ContractErrorCode` | `core/core/exception` |
| 错误信封 | `ApiError` / `ContractErrorHandler` | `core` / `seedj-biz-starter/biz/web` |
| 审计 v1.1 字段与算法 | `AuditEntry.payloadDigest` / `CanonicalHasher` | `core/audit` |
| 授权绑定（role + scope + 双时间轴） | `IamBinding` | `core/iam` |
| 代理 | `IamProxy` | `core/iam` |
| 解析结果 | `IamResolution` | `core/iam` |
| 权限判定结果 | `IamCheckResult`（allowed + reason + reasonCode） | `core/iam` |
| 职责分离 | `SodRule` / `SodEvaluator` / `SodFinding` | `core/iam/policy` |
| 属性快照 | `AttributeSnapshot` | `core/iam` |
| 派生（writeOnly）字段 | `DerivedField` / `DerivedFieldKeyRing` | `core/core/security` |
| 合规工件与门禁 | 只在 `docs/conformance/`、`config/conformance/` 使用“合规/conformance”字样 | 非产品代码 |

命名纪律：

- 产品代码（`src/main/java`、`src/main/resources`、SQL）里不出现 `eis`（例外见 §2.1）。
- 测试代码可以用“conformance”描述它验证什么，但不出现 `eis` 作为类型名（例外同上）。
- 文档（`docs/`）可以正常书写规范名，否则合规声明没法写。

---

## 6. 模块落点

| 缺口 | 能力 | 模块 | 包 | 交付物 |
|---|---|---|---|---|
| G1 | 双时间轴对象模型与存储 | `core` | `domain` | `ObjectVersion`、`TemporalRules`、`Sealing`、`TemporalQuery`、`TemporalStore` |
| G1 | PG 对象表与查询 | `seedj-jdbc-postgresql` | `domain.jdbc` | `PostgresTemporalStore`、`TemporalMigrations`（DDL + 时点函数） |
| G1 | 内存对象存储 | `seedj-infra-local` | `domain.memory` | `InMemoryTemporalStore` |
| G5 | UUIDv7 | `seedj-infra-local` | `id` | `Uuid7IdGenerator` |
| G2/G3 | 命令运行时 | `core` | `cqrs`、`cqrs.spi` | `CommandRecord`、`CommandReceipt`、`IdempotencyFingerprint`、`CommandStore`、`TemporalCommandAgent` |
| G2/G3 | PG 命令表 | `seedj-jdbc-postgresql` | `cqrs.jdbc` | `PostgresCommandStore`、`CommandMigrations` |
| G2/G3 | 内存命令存储 | `seedj-infra-local` | `cqrs.memory` | `InMemoryCommandStore` |
| G2/G3 | 命令与查询 HTTP 面 | `seedj-biz-starter` | `cqrs.web`、`domain.web` | `CommandController`、`ObjectQueryController`、`ObjectQueryResponse` |
| G4 | 错误码与信封 | `core`、`seedj-biz-starter` | `core.exception`、`biz.web` | `ContractErrorCode`、`ApiError`、`ContractErrorHandler`；改造 401/403 处理器 |
| G6/G7 | 审计 v1.1 | `core`、`seedj-jdbc-postgresql`、`seedj-biz-starter` | `audit`、`audit.jdbc`、`audit.wiring` | `payloadDigest` + 事件复算、验证器错误类型、DDL 对齐、链头锚定任务 |
| G8/G9/G10 | 授权对象与判定 | `core`、`seedj-infra-local`、`seedj-jdbc-postgresql` | `iam`、`iam.memory`、`iam.jdbc` | `IamBinding`、`IamProxy`、`IamResolution`、`SodEvaluator`；`iam_binding` / `iam_proxy` 表与迁移 |
| G9 | 授权 API | `seedj-iam-starter` | `iam.web`、`iam.authz` | `IamAuthorizationController`（`/resolve`、`/check`） |
| G11 | 属性快照 | `core`、`seedj-iam-starter` | `iam`、`iam.abac` | `AttributeSnapshot`、`IamAttributeSource` SPI、快照缓存（键含 principal/as_of/recorded_as_of） |
| G13 | 派生字段 | `core`、`seedj-biz-starter` | `core.security`、`biz.derived` | `DerivedField`、密钥环、写路径派生与投影/命令记录/日志剔除 |
| G12 | 演进门禁 | `config`、`seedj-it`、`docs/conformance` | — | 兼容规则、迁移/回滚模板、`-Pconformance` 门禁、弃用窗口政策 |
| G14 | Outbox/Inbox 对齐 | `core`、`seedj-jdbc-postgresql` | `outbox`、`outbox.jdbc` | `dead`/聚合字段/Inbox 落库；保持既有 API 兼容 |
| G15 | 活样板与对照 | `seedj-server/hr`、`seedj-it` | `server.hr`、测试 | 员工对象（双时间轴 + 命令 + `as_of` + 审计）、契约用例移植、CI 挂钩 |

依赖检查：以上没有任何新模块；`seedj-biz-starter` 不引入 `seedj-iam-starter`（命令面通过
`core` 的授权判定 SPI 调用，`hr` 宿主用本地策略/静态属性实现，`iam` 宿主用真实 IAM）。

---

## 7. 关键设计

### 7.1 写模型：对象表权威（方案 A 的 D1 决策）

**决策**：业务对象表是权威写模型；事件是同一事务内的副产品（Outbox）。事件溯源保留为可选 profile。

一次命令写入的事务顺序（由 `TemporalCommandAgent` 保证，不靠文档）：

```
拦截器 before
  lock(objectType:id)                     ← 事务外，提交后才释放
  begin transaction
    幂等：insert command_record(idempotency_key 唯一) 冲突 → 回读比对指纹
    加载当前行 FOR UPDATE（superseded_at IS NULL）
    规则校验（T-R001..011、领域不变量）
    封口：旧行 superseded_at = now（不改 valid_*）
    追加：新行 version+1，recorded_at = now，valid_from/valid_to 按意图
    命令终态：accepted（含 locator = 对象 id，version）
    审计：append（payload_digest = 本命令事件投影的 SHA-256）
    Outbox：append（事件信封，与对象行同事务）
  commit
  释放锁
```

要点：

- **`recorded_at` 严格单调**：同一 `id` 的新 `recorded_at` 若 `<=` 当前行，取 `当前值 + 1µs`。
  否则 `PK(id, recorded_at)` 会因同一微秒的两条追加冲突。
- **PK 与约束**：`PRIMARY KEY (id, recorded_at)`；`UNIQUE (id) WHERE superseded_at IS NULL`；
  `EXCLUDE USING gist (id WITH =, tstzrange(recorded_at, superseded_at, '[)') WITH &&,
  tstzrange(valid_from, valid_to, '[)') WITH &&)`；三个时点索引。约束名用 `uq_<table>_current` /
  `ex_<table>_valid_overlap`，与规范一致（不含 `eis`）。
- **迟到事实**：`appendLateFact` 只改系统时间轴（封口旧行），允许新行的业务区间覆盖历史区间；
  已记录的 `valid_*` 不得回改（T-R011）。
- **业务时间区间变更**：`valid_from/valid_to` 变化也走“封口 + 追加”，不 UPDATE 旧行。
- **tenant**：不进冻结元结构；作为对象表的附加列（`tenant_id`），由 `TemporalStore` 在写读两侧
  透明注入；不做成元字段。

### 7.2 命令运行时

- 既有 `CommandAgent` **不改**（事件溯源路径）；新增 `TemporalCommandAgent`，复用其
  `Options`（事务/锁/重试/拦截器/审计 seam）与执行顺序纪律。
- `CommandRecord` 持久化：`id, type, issued_at, issuer, payload(脱敏), idempotency_key,
  payload_fingerprint, status, rejection_code, rejection_reason, event_ids, locator, version, created_at`。
- 幂等：`payload_fingerprint = SHA-256(canonical{type, payload(脱敏前), targets})`；**只存摘要**。
  重复键 + 指纹一致 → 回放原终态（202/400 同原状）；指纹不一致 → `409` + `EIS-409-001`，
  返回原 `command_id` 与原结果。并发同键由唯一索引裁决：捕获唯一冲突后回读重放。
- **拒绝路径必须落库**：业务事务回滚时，`rejected` 的命令记录用独立事务（`REQUIRES_NEW`）写入，
  否则 `GET /commands/{id}` 查不到被拒命令。幂等键在拒绝后仍然占用（重放返回原拒绝）。
- 命令类型到对象类型的映射由 `TemporalCommandAgent` 子类声明（每个命令一个子类，与既有
  `CommandAgent` 的用法一致）；框架不做字符串约定。
- 响应：成功 `202 {command_id, status:"accepted", event_ids, locator, version}`；不携带业务字段
  （ADR-0014）。失败 `400 {command_id, status:"rejected", reason, code}`。
- `GET /commands/{id}` 返回终态（`pending|accepted|rejected`）；404 用 `EIS-404-001`。

### 7.3 时点查询

- `GET /objects/{objectType}/{id}` 支持 `as_of`、`recorded_as_of`、`min_version`。
- 过滤在 SQL：半开区间 `from <= t AND (to IS NULL OR to > t)`；缺省 = `superseded_at IS NULL`
  且 `valid_to IS NULL`（当前）。
- 同一实体单版本（T-R008）：命中多条时取 `version` 最大者（与内核一致），DB 约束保证不重叠。
- `min_version` 未追平：`409` + `reason_code=EIS-409-003` + `Retry-After`（默认 1 秒，可配），
  **不得**返回旧版本。历史参数与 `min_version` 组合时按同一规则比较返回版本。
- 读投影剔除 `writeOnly` 字段的原始值与派生值（DRV-004）。
- 规范里的 `as_of_valid/as_of_recorded/as_of_bitemporal` SQL 函数随迁移创建，作为跨语言对照
  与运维手工查询入口；Java 侧按对象表拼 `WHERE`，不用 `SETOF record` 动态函数（类型不安全）。

### 7.4 错误码与信封

- 信封固定 `{code, message, details?}`；`code` 为 `EIS-{status}-{seq}`（唯一允许的 `EIS` 字面量）。
- `ContractErrorCode` 枚举承载码表（12 个码），并提供与既有 `CqrsErrorCode` / `IamErrorCode` /
  `SecurityErrorCode` / `AuditErrorCode` / `InfrastructureErrorCode` 的映射；**不替换**既有枚举。
- 401/403 改造 `JsonAuthenticationEntryPoint` / `JsonAccessDeniedHandler` 走同一信封；
  403 必须带 `reason`（来自 `/check`）。
- 500 带 `traceId`、不带内部细节；503 用于存储/事件通道不可用（命令未接受，可安全重试）。

### 7.5 事件与 Outbox

- `EventEnvelope` 字段与 `event.schema.json` 一致；`idempotency_key` 格式
  `{command_id}:{rule_id}:{action_index}`。
- 事件与对象行、命令终态、审计在同一事务内落库（Outbox 保证投递）。
- 事件通道 `events.{eventType}`；负载引用 `eis://core/event/v1`（§2.1 例外）。
- G14 的 Outbox 对齐（`dead` 状态、聚合字段、Inbox 落库）在此阶段一并做，保持既有
  `OutboxWriter` / `OutboxRelay` API 不变，只加列与状态。

### 7.6 审计 v1.1

- `AuditEntry` 增加可空 `payloadDigest`；`AuditAppendRequest` 增加 `eventIds`。
- 规范化：`payload_digest` 存在时作为第 11 行追加；缺失时保持 v1.0 公式——**旧条目哈希逐字节不变**。
- 摘要 = `SHA-256(canonical_json([{id,type,payload}...]))`，按 `event_ids` 顺序；空列表用冻结常量。
- 验证器新增 `payload_digest_mismatch` / `payload_digest_missing_event`，入口接收“事件导出”。
- DDL：`audit_entry` 加 `payload_digest` 列、长度 `CHECK`、`sequence` 唯一、`PK(id)`、
  append-only `RULE`（禁 UPDATE/DELETE）；列名 `occurred_at` → `timestamp`（迁移重命名）。
- 锚定：保留 `AuditAnchor` 接口；新增可选定时锚定任务（默认关闭），签名/发布属部署策略。
- 覆盖：`TemporalCommandAgent` 对每条受理命令都审计（不看 `@Audited`），payload 含
  `{command_id, event_ids}`；既有事件溯源路径的 `AnnotationCommandAudit` 行为不变。

### 7.7 授权

- 对象模型：`principal`（复用 `IamSubject`/`IamUser`）、`role`（复用 `IamRole`）、
  `IamBinding`（`id, principal, role, scope{organization,department,warehouse},
  valid_from, valid_to, recorded_at, superseded_at`）、`IamProxy`（`id, principal, agent,
  valid_from, valid_to, recorded_at, superseded_at`）。
- 存储：`iam_binding` / `iam_proxy` 表（双时间轴 + 排他约束，规则同 §7.1）；内存实现供测试。
  既有 `iam_user_role` 不动；提供可选回填迁移（`iam_user_role` → `iam_binding`，scope 取部署默认）。
- `/resolve`：按 `as_of` 解析有效绑定 → 角色继承 → 权限集合；代理展开；输出 `roles`、
  `permissions`、`sod_warnings`。
- `/check`：`principal + permission + as_of` → `{allowed, reason, reason_code}`。判定顺序：
  1) 是否持有权限；2) SoD `prevent` 冲突（`reason_code=SOD-xxx`）；3) 声明式条件求值
  （只收窄）；4) 调用方主体类型约束（`EIS-403-002`，fail-closed）。
- SoD：规则模型通用（`conflicting_permissions` + `scope` + `enforcement`），默认内置
  SOD-001..003 语义，可用配置覆盖；`prevent` 拒绝，`warn` 进 `sod_warnings`。
- 属性快照：`AttributeSnapshot{primary_position, positions, as_of, recorded_as_of, expires_at,
  source_version}`；缓存键三元组；缺失/过期/源不可达/不匹配分别返回
  `ATTRIBUTE_MISSING` / `ATTRIBUTE_STALE` / `ATTRIBUTE_SOURCE_UNAVAILABLE` / `ATTRIBUTE_MISMATCH`，
  一律 fail-closed（权限不依赖属性时不受影响）。
- 属性端点 `GET /principals/{id}/attributes`：仅服务身份 + 属主声明的 service 权限；不暴露给浏览器。

### 7.8 派生（writeOnly）字段

- 对象 Schema 标记 `writeOnly` → 生成 `DerivedField` 注册；写路径对原始值做
  `HMAC-SHA256(主密钥, scope + 0x00 + 原始值)`，scope = `seedj:<ext>:<object>:<field>:v1`。
- 密钥环来自环境变量 `SEEDJ_FIELD_HASH_KEYS`（逗号分隔、首为主）；生产必须注入，缺省值仅测试。
- 幂等指纹基于脱敏前载荷，但只存 SHA-256；命令记录、读投影、事件、日志、错误响应一律剔除
  原始值与派生值。
- 轮换：防重/比对覆盖密钥环全部接受密钥的散列变体；旧密钥保留到无在库依赖。

### 7.9 装配与配置（遵守 SPI 宪法 R3/R4）

新能力沿用 `docs/spi/README.md` 的约定：实现模块自带条件化 `@AutoConfiguration` 并登记到
`AutoConfiguration.imports`；每个能力两个配置键；bean 名即 provider 名。

| 能力 | 配置键 | 默认 | 装配位置 |
|---|---|---|---|
| 对象存储 | `seedj.object.enabled`、`seedj.object.provider` | true / `memory`（有 `DataSource` 时 `postgres`） | `seedj-biz-starter/domain.wiring` |
| 命令运行时 | `seedj.command.enabled`、`seedj.command.provider` | true / `memory`（有 `DataSource` 时 `postgres`） | `seedj-biz-starter/cqrs.wiring` |
| 授权 | `seedj.authz.enabled`、`seedj.authz.provider` | true / `memory`（有 `DataSource` 时 `postgres`） | `seedj-iam-starter/iam.authz` |
| 派生字段 | `seedj.derived.enabled` | false（需显式开启并注入密钥环） | `seedj-biz-starter/biz.derived` |
| 审计锚定 | `seedj.audit.anchor.enabled` | false | `seedj-biz-starter/audit.wiring` |

- 开启但缺依赖（如 `provider=postgres` 无 `DataSource`）启动失败，不静默降级。
- 配置键与 bean 名不含规范名。
- `seedj-server/hr` 的对象 Schema 与建表迁移放本模块 `src/main/resources`，由
  `JdbcMigrationRunner` 在启动时执行；不放进通用 starter。
- 余额查询端点（契约里的 `GET /balances/{balanceType}`）由扩展域提供：`core` 定义
  `BalanceQuery` SPI，starter 仅在存在 provider 时注册端点，通用内核不自造余额语义。

### 7.10 演进（L4）

- 工件：`docs/conformance/schema/`（对象/事件/命令/视图 Schema）、`docs/conformance/api/`
  （OpenAPI、AsyncAPI）、`docs/conformance/baseline/`（发布基线快照）。
- 门禁：`config/conformance/compatibility.rules.yaml`；Maven profile `-Pconformance` 在
  `seedj-it` 里调用 `python3 ${spec.root}/tools/compatibility-checker --fail-on MAJOR`
  （`spec.root` 默认 `../bf/eis`，可用 `-Dspec.root=` 覆盖）。规范工具缺席时 profile 显式失败，
  不静默跳过。
- 迁移：`V{version}__{description}.sql` + 成对 `rollback/V{version}__{description}.rollback.sql`；
  `JdbcMigrationRunner` 增加回滚执行与演练测试（结构断言 + 计数校验）。
- 弃用窗口：政策文档 + 变更清单模板（≥2 MINOR 且 ≥90 天）；新增 ADR 模板要求引用兼容规则条目。

---

## 8. 兼容与迁移

| 变更 | 兼容策略 |
|---|---|
| 审计表加 `payload_digest`、列改名 | v1.0 条目无 digest 仍可验证；迁移重命名列并保留数据 |
| `AuditEntry` 构造器加字段 | 保留旧构造器重载，标注弃用 |
| IAM 新增 binding/proxy 表 | 纯增量；`iam_user_role` 不动，回填可选 |
| 命令表 | 新增表，不与 Outbox 混用 |
| 既有 `CommandAgent` | 不改行为；`TemporalCommandAgent` 是并列实现 |
| Outbox 加列/状态 | 增量列 + 默认值；relay 对未知状态按 `PENDING` 处理 |
| 错误信封 | 旧 `{error,message}` 路径改为新信封；保留字段映射，不保留双格式 |

---

## 9. 分期与退出标准

| 阶段 | 内容（缺口） | 退出标准 |
|---|---|---|
| P0 | 命名清理（4 处 javadoc）、`ContractErrorCode`、UUIDv7（G5） | `grep -ri eis` 在产品代码中只剩 §2.1 例外；UUIDv7 单调性测试通过 |
| P1 | 双时间轴对象模型 + PG DDL + 内存实现（G1） | PG16 Testcontainers：区间/封口/单当前/排他/单版本断言全过；DDL 冒烟通过 |
| P2 | 命令运行时 + HTTP 面 + 错误信封 + `as_of`/`min_version`（G2/G3/G4） | MockMvc：202/400/409、幂等重放、`GET /commands/{id}`、`Retry-After`；OpenAPI 对照通过 |
| P3 | 审计 v1.1 + DDL 对齐 + 锚定（G6/G7） | 两个冻结向量逐位一致；篡改/缺口/断链/摘要错误用例通过；append-only 拒绝 UPDATE/DELETE |
| P4 | 授权对象 + `/resolve` `/check` + SoD + 属性快照（G8/G9/G10/G11） | 时点/代理/SoD/属性 fail-closed 用例通过；`authz-resolver --self-test` 语义等价 |
| P5 | 演进门禁 + 迁移/回滚 + 弃用窗口（G12） | `compatibility-checker --fail-on MAJOR` 绿；迁移/回滚演练通过 |
| P6 | 派生字段、Outbox/Inbox、hr 活样板、对照测试与声明（G13/G14/G15） | `seedj-it` 全量契约对照绿；hr 端到端演示；合规声明写入 README |

每阶段结束时 `mvn -o -B verify` 必须绿；跨阶段不合并提交。

---

## 10. 验证策略

**实现侧（Java）**

- 冻结向量：创世 v1.0、v1.1 带 `payload_digest` 两个哈希逐位比对。
- 契约用例移植：把 `03/04/06` 的 `*.test.yaml` 与 `cases/procurement` 的关键断言翻译成 Java 测试
  （对象/时点/命令/授权/迁移）。
- 黄金文件回放：用规范内核生成一次期望输出（JSON），签入 `seedj-it/src/test/resources/`；
  Java 测试回放，不在测试期依赖 Python。
- PG16：Testcontainers 跑真实约束与迁移；内存实现跑同一套契约 TCK（沿用 `core` 的 test-jar 模式）。

**规范侧（CI，可选 profile）**

- `schema-compiler check`、`contract-checker`、`audit-verifier --vector`、
  `authz-resolver --self-test`、`compatibility-checker --fail-on MAJOR`、`ci/ddl-smoke.sql`。
- 运行记录写入 `docs/conformance/runs/YYYY-MM-DD.md`（命令、版本、结果、差异说明）。

**门禁**

- `seedj-it` 增加“产品代码不得出现规范名”的静态检查（只看 §2.1 例外），防止回潮。
- `seedj-biz-starter` 与 `seedj-iam-starter` 的包独立测试继续生效。

---

## 11. 风险

| 风险 | 影响 | 应对 |
|---|---|---|
| 双写模型不一致 | `recorded_at` 语义漂移 | 对象行/命令/审计/事件同事务；`TemporalCommandAgent` 固定顺序；PG 约束兜底 |
| 审计串行写吞吐 | 单链吞吐上限 | 规范语义要求连续序列；部署侧评估，不做分片 |
| IAM 表扩展 | 已部署数据 | 新表增量 + 可选回填；不动既有表 |
| `btree_gist` 依赖 | 迁移失败 | 迁移首句 `CREATE EXTENSION IF NOT EXISTS btree_gist`；Testcontainers 用官方 PG16 镜像 |
| 规范工具是 Python | CI 环境 | 只在 `-Pconformance` 与发布前运行；黄金文件保证日常测试不依赖 |
| `seedj-server-hr` 无 IAM | 授权演示不完整 | hr 用本地策略/静态属性实现 `core` 的判定 SPI；`/resolve` `/check` 的完整演示放 `seedj-server-iam` |
| 15 个缺口跨度大 | 半成品 | 分期退出标准 + 每期 `verify` 绿；不允许跨期合入 |

---

## 12. 交给实现者的判断（我的默认已标注）

1. `TemporalCommandAgent` 是否继承 `CommandAgent`：**默认不继承**（写入模型不同，继承会带来无效
   分支）；复用 `Options`、拦截器与审计 seam。
2. `TemporalStore` 的 SPI 形状（按对象类型注册 mapper，还是泛型 + 行映射器）：**默认泛型 +
   `ObjectRowMapper<T>`**，对象类型到表名的映射由配置提供。
3. OpenAPI/AsyncAPI 工件是手写还是生成：**默认手写**在 `docs/conformance/api/`，用 MockMvc +
   反射测试保证与控制器一致；不引入 springdoc。
4. 迁移文件编号起点：**默认沿用 `JdbcMigrationRunner` 现有编号序列**，新增按 `V{n}` 递增。
5. 属性快照的属主来源：**默认 `IamAttributeSource` SPI + 配置化 provider**，hr 样板用静态文件，
   iam 宿主用 IAM 仓储。
6. `docs/conformance/` 与 `config/conformance/` 是否可接受：**默认可以**（不是模块）；若你要求
   连目录也不新增，则改放 `docs/adr/` 与 `config/` 根下，代价是可读性下降。
