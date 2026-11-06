# 架构评估（2026-11-06）：四条轴线的独立复核

- 日期：2026-11-06
- 状态：已复核（独立评估，不是验收记录）
- 代码基线：`read-path-cqrs-ergonomics` 分支 `c9897c2`（相对 `main` 的 `1eb5386`）
- 评估对象：`seedj-server/hr` 作为「业务开发人员日常项目样式」，以及它依赖的
  `core` / `seedj-infra-local` / `seedj-jdbc-postgresql` / `seedj-biz-starter`
- 关联记录：[A- 验收记录（2026-11-06）](../conformance/runs/2026-11-06-a-minus.md)、
  [覆盖率差距（2026-11-06）](2026-11-06-coverage-gap.md)、
  [ADR-0008](../adr/ADR-0008-module-topology.md)、
  [ADR-0009](../adr/ADR-0009-package-topology-and-api-convergence.md)

## 0. 口径与限制

本页回答四个问题：架构合理性、与业内工程规范的差距、业务开发人员认知负担、架构整洁程度。
评的是「**作为业务开发基础设施/平台，能否让普通业务团队安全、低成本地交付**」，
不是「代码是否优雅」。

限制：

- 本次评估以静态通读与仓库内可复核证据为主。本机离线 Maven 仓库缺少 `lombok` / `slf4j`
  制品，`./mvnw -o` 未能完成全量构建，因此**不宣称构建/测试绿灯**。
- 覆盖率、测试数量引用 [A- 验收记录](../conformance/runs/2026-11-06-a-minus.md) 的实测值，
  并明确标注来源；其余结论均可由本页「证据索引」中的文件直接复核。

规模事实：8 个 Maven 模块、主源码 43,605 行、`config/public-api.txt` 575 个 public 类型；
`core` 一个 jar 承载 14 个能力域（workflow 67 个 public 类型、iam 66、transit 32）。

## 1. 总评

| 维度 | 评级 | 一句话结论 |
|---|---|---|
| 架构合理性 | **B** | 内核（双时间轴 + 读网关 + 命令运行时）有真正的深度；平台边界失控，查询表达力是 day-2 硬天花板 |
| 与业内工程规范差距 | **B** | 门禁与文档高于行业平均；覆盖率、API 生成、自研 vs 采购的决策落后 |
| 业务开发人员认知负担 | **C-** | 一个聚合 = 18 个主文件、960 行、13 个 `@Bean`、37 个框架类型、14 个包 |
| 架构整洁程度 | **B-** | 机械整洁度高；语义整洁被重复、未接线 API、文档失真拉低 |
| **综合** | **B** | **未达到自定的 A-；不建议以「业务开发可直接按模板交付」作为 1.0 卖点** |

关键定位判断：README 把它定位为「基础设施/基础类库」，实际形态是
「业务运行时 + 数据平台 + IAM 服务器」的合集。用类库标准看它太重；用平台标准看，
它缺 day-2 能力（自定义查询、迁移演进、生产消息、工作流持久化）。这个定位错位是
后续多数问题的根。

## 2. 架构合理性：B

### 2.1 做对的部分（A- 级）

- **`Sealing` + `TemporalDdl`**：双时间轴的封口、版本、系统时间单调性、DDL/排他约束/
  时点索引全部收口在一处。调用方只给业务字段与业务时点，复杂度没有泄漏给业务代码。
  这是整个仓库最有价值的设计。
- **`ReadGateway`**：对象权限、行范围、字段策略、脱敏、审计只有一个入口，失败关闭语义
  明确（未知对象 404、无 resolver 403、resolver 异常 503）。内存与 PostgreSQL 两个实现
  共享 `TemporalStoreContractTest`，是真正的契约测试。
- **`CommandRuntime`**：幂等键 → 解码 → 校验 → 锁 → 事务 → 写范围 → `decide` →
  结果范围 → append → outbox → 终态，顺序被写成契约并有测试。命令级权限、脱敏、审计
  在运行时收口，而不是散在 Controller。
- **错误模型**：`ContractException` + 冻结错误码 + 单一 `ContractErrorHandler`，
  `reason_code`、`Retry-After` 语义明确。
- **迁移执行器**：`JdbcMigrationRunner` 具备 advisory lock、checksum、`validate`、
  `repair`、事务内执行，质量不低于一个简化版 Flyway。
- **可执行门禁**：`ArchitectureTest`、`SplitPackageTest`、`PackageCycleTest`、
  `PublicApiSurfaceTest`、`FileSizeGuardTest`、`DocLinkTest`、`NamingGuardTest` 都是
  断言而不是文档约定。

### 2.2 结构性问题（按影响排序）

#### P0-1 查询表达力天花板

`ReadGateway` 只提供 `find` / `search` / `history`；`TemporalSearch` 只支持单表等值过滤、
单字段排序、offset 分页，没有 join、聚合、`IN`/范围/全文，也没有「自定义查询」的
sanctioned seam；`ScopeSql` 是包私有的，业务无法复用行范围编译。`docs/read-model.md`
明确写「所有业务读只经 `ReadGateway`」，`ArchitectureTest` 还禁止业务直接用
`TemporalStore`。

后果：

- 真实需求（员工 + 组织名称 join、部门人数统计、模糊搜索）在框架内写不出来；
- 业务只能直接用 `JdbcTemplate`，而这条路径绕过对象权限、行范围、字段脱敏、读审计；
- 框架用门禁堵住了不安全的写法，却没有提供安全且够用的写法。

这是当前架构最大的产品风险。

#### P0-2 两套写模型能力不对等，文档却宣称共享

- 默认路径：`CommandHandler` + `CommandRuntime` + `AppendRequest` + `TemporalStore`，
  有幂等、命令记录、脱敏、权限、outbox、审计。
- advanced 路径：`CommandAgent` + `Repository` + `AbstractAggregateRoot` + `DomainEvent`，
  **没有**幂等键、没有命令记录、没有 redaction、没有 `CommandDescriptor`、没有 outbox。
  `CommandAgent` 中 `idempot` / `permission` / `redact` / `CommandStore` /
  `CommandDescriptor` 的出现次数均为 0。
- 通用 `/commands` 入口被 ArchUnit 明确禁止接 `CommandAgent`；事件溯源只能自写 Controller
  （示例在 `seedj-biz-starter/src/test/java/cn/byteforce/seedj/demo/hr`）。
- `docs/decision-guide.md` 却写「两条路径共用 `CommandRuntime`、`CommandDescriptor`、
  权限、错误信封与 outbox」——事实错误。

#### P0-3 schema 单一来源与迁移模型自相矛盾

`HrEmployeeSchemaCapability` 的 V1 是
`TemporalDdl.createTable(TemporalTable.from(当前 schema))` 在启动时动态生成的；
`JdbcMigrationRunner` 的 checksum 覆盖 SQL 文本。

因此：给 schema 加一个字段 → V1 的 SQL 文本变化 → checksum 变化 → 已有数据库启动失败
（`migration V1 checksum mismatch`）。该类的 javadoc 却写「后续加字段用 V2
`ALTER TABLE ... ADD COLUMN`，不修改 V1 内容」。仓库只有 V1，没有 V2/升级测试，
这个坑未被暴露。

#### P0-4 schema 是「描述性」的，不是「规范性」的

| 声明 | 是否强制执行 | 证据 |
|---|---|---|
| `filterable` / `sortable` | 是，两个 store 都校验 | `InMemoryTemporalStore`、`PostgresTemporalStore` |
| `writeOnly` 不进读投影 | 是，`ReadGateway` 渲染时剔除 | — |
| `writeOnly` 不进命令记录 | 否，靠每个命令手动挂 `redactor`，默认 `noop` | `HrEmployeeModule` 手动传 `redactor` |
| `writeOnly` 不进事件载荷 | 否，靠 handler 自己用 `HrEmployeeEvents.payload()` | `CommandRuntime.writeEvents` 不过滤 |
| `required` 字段 | 否，`ObjectSchema.validate()` 生产代码零调用 | 只有 `ObjectSchemaTest` 调用 |
| `creates` / `emits` | 否，运行时完全不读 | 只有 `ObjectModulesCommandTest` 断言 |
| 未知字段拒绝 | 否；PostgreSQL 静默丢弃，内存实现保留 | `PostgresTemporalStore.insert` 只遍历 `table.columns()` |

最后一条是适配器语义分歧与数据静默丢失：handler 写错字段名，本地内存模式能跑、
PostgreSQL 里字段消失，而 `ObjectSchema.validate()` 正是为此写的却没接线。

#### P0-5 分布式锁 + 行锁双保险，失败模式映射错误

- `CommandRuntime` 先取分布式锁，`PostgresTemporalStore.append` 又用
  `SELECT ... FOR UPDATE` 锁同一行；两个适配器本身都已按对象串行化。
- 锁是非阻塞的：`acquire()` 抢不到直接抛 `LOCK_BUSY`；默认路径没有重试（只有
  `CommandAgent` 有 `maxAttempts` 重试）。
- `LockException extends InfrastructureException` → `ContractErrorHandler` 统一映射
  503 `EIS-503-001`。两个用户同时改同一员工，第二个得到「依赖不可用」，不是 409/429。
- 锁租约默认 30s；进程崩溃后该对象的写入被阻塞最多 30s。

#### P1-6 `core` 是平台，不是契约包

`workflow` 67 个类型 + 2,625 行内存引擎，全仓没有产品消费者（只有自己的测试和
`SpiSurfaceTest` 一行 import）。`transit` 32 个类型只有 BouncyCastle 一个实现；
`pki` 3 个类型只有一个实现。任何只想用双时间轴 store 的业务应用，必须把 IAM/workflow/
transit/pki 的 575 个 public 类型全部放进 compile classpath。

#### P1-7 `CQRS` 名不副实

默认路径的读侧直接读同一张对象表，没有投影、没有读模型追平；`READ_MODEL_NOT_READY`
只在事件溯源 demo 里有实际意义。它是「命令/查询 API 分离 + 双时间轴」，不是 CQRS。

### 2.3 结论

内核 A-；平台边界 C+；综合 B。短板不在「写得不够优雅」，而在「产品定义不收敛」。

## 3. 与业内工程规范的差距：B

### 3.1 高于行业平均的部分

架构门禁可执行；契约测试被两个实现复用；迁移器语义完整；幂等 + outbox + 审计链；
单一错误映射；10 篇 ADR 与完整评估记录；Spotless/SpotBugs/JaCoCo ratchet/SBOM/japicmp；
全仓 0 条 TODO/FIXME。

### 3.2 差距

| 维度 | 现状 | 业内基线 | 判断 |
|---|---|---|---|
| 覆盖率 | core 52.8% / iam 53.6%（A- 记录），目标 80/75 | 核心库 80%+，安全组件 85%+ | 不达标且核心抽象覆盖最低 |
| 变更行覆盖 | diff-cover 已接，conformance 不在 PR | PR 必跑 | 合并门禁缺口 |
| 规范对照 | conformance job 只在 `schedule` / `workflow_dispatch` | PR/push 必跑 | 合并门禁缺口 |
| API 文档 | `docs/conformance/api/*.yaml` 手写；无 springdoc | 从注册表生成 OpenAPI 3.1 | 会漂移，typed query 端点不可发现 |
| 可观测性 | 自研 `TracePropagation` + Micrometer 包装 | OpenTelemetry 标准 | 自研维护成本 |
| 迁移 | 自研 runner | Flyway / Liquibase | 可接受，但缺 baseline/undo/CLI，且 V1 checksum 有洞 |
| IAM | 自研 OIDC + SCIM + RBAC + ABAC + MFA（71 文件） | Keycloak / Authentik / 云 IdP | 53% 覆盖率的安全产品，风险极高 |
| 消息 | eventbus 只有 memory/file transport | Kafka / RabbitMQ / Pulsar | 生产不可用 |
| 工作流 | 只有内存实现，零消费者 | Camunda / Temporal / Flowable | 68 个 SPI 类型没有产品价值 |
| 映射 | 手写 SQL + 反射 `ViewMapper` | jOOQ / JPA / MyBatis / MapStruct | 反射映射无编译期校验 |
| 构建产物 | 有 SBOM/japicmp，无容器镜像、无 `dependency:analyze`、无 SAST/secret 扫描 | 容器 + 供应链 + SAST | 发布链路不完整 |
| 性能 | 无基准；列表查询是 `DISTINCT ON` 子查询 + 每页 `COUNT(*)` | keyset 分页、可选 total、基准测试 | 规模化风险 |

### 3.3 采购 vs 自研

- 自研合理：双时间轴对象存储、审计哈希链、幂等命令记录、迁移器。
- 自研不合理：OIDC/SCIM 授权服务器、工作流引擎、事件传输、通用 ORM/映射。

这些自研项不是产品差异化，却占用了最多的安全面与维护预算。尤其 IAM：RFC 一致性、
密钥轮换、令牌撤销、SCIM 过滤、MFA 限流，任何一项出问题都是安全事故；而它的覆盖率
只有 53%。

## 4. 业务开发人员认知负担：C-

### 4.1 一个聚合的真实成本（HR 样板）

| 指标 | 实测 |
|---|---|
| 主源码 | 18 个文件 / 960 行（含 3 个本地开发安全类） |
| 测试 | 4 个文件 / 637 行，全部 `@SpringBootTest` |
| `@Bean` | 13 个（业务配置 10 个） |
| 框架 import | 37 个类型 / 14 个包（不含 3 个 dev-auth 类） |
| 业务能力 | 2 条命令、1 个视图、1 个状态机 |
| 配置 | 3 个 profile YAML，约 30 个 `seedj.*` 开关 |

对比业内：Spring Data JPA + Bean Validation + springdoc 做同样的 CRUD，约 4-6 个文件、
150-250 行。即使考虑双时间轴和 CQRS 的额外成本，960 行 / 37 个框架类型仍然显著超标。

### 4.2 加一个字段要动几处

框架承诺 C2「加字段只改 ≤3 个文件」。实际路径：

1. `HrEmployeeModule`：`.field("address", ...)`
2. `HrEmployeeCreate` record + `HrEmployeeCreateHandler`：`fields.put("address", ...)`
3. `HrEmployeeUpdate` record + `HrEmployeeUpdateHandler`：`changes.put(...)`
4. `HrEmployeeView` record
5. `HrEmployeeSchemaCapability`：加 V2 `ALTER TABLE`（且 V1 会 checksum 失败）
6. `HrEmployeeTestSchema`：测试侧再抄一遍（已漂移：少了 `phone`，少了 `indexed/sortable`）
7. 测试 + 文档 + `commands.yaml`（如果是新命令）

实际是 6-8 处，跨 5 个包。这不是「schema 单一来源」，而是「一个字段在 schema、handler、
view、migration、test fixture 之间手工同步」——正是 `ObjectSchema` javadoc 声称要消灭的事。

### 4.3 样板正在教坏习惯

| 样板写法 | 应该示范的写法 |
|---|---|
| `requireName()` / 手写 blank 检查 | `BeanValidationCommandValidator` 已自动装配却没用；`@NotBlank` 即可 |
| `ctx.caller().attributes().get("org_units")` 裸 Map | 强类型 `ScopeKey` + `ContextWriter`（后者已存在，handler 又绕过） |
| `Map<String,Object> fields` + 字符串键 | 类型化字段常量/生成器，或至少 `schema.validate()` |
| 状态机写在 `HrEmployeeUpdateHandler` 的 `switch` 字符串里 | 聚合方法或显式状态枚举 |
| 业务规则只能通过 `@SpringBootTest` + HTTP 测 | 纯函数策略对象，`CommandContext` 可构造 |
| dev-auth 3 个文件抄进宿主 | starter 提供 `@Profile("local")` 的 dev-auth，宿主只开开关 |
| 测试 schema 复制一份并声称有 conformance 测试 | 声明与 Spring 解耦，测试直接复用同一份声明 |

`HrEmployeeTestSchema` 的 javadoc 说「两者一致由 HR 的 schema conformance 测试保证」，
但全仓不存在这个测试，而且它已经和生产 schema 漂移。

### 4.4 概念税

业务开发者要同时理解：2 套写模型、2 套事件模型（`CommandEvent` / `DomainEvent`）、
2 套审计（`TemporalCommandAudit` / `CommandAudit`）、2 套校验（`decide()` 手写 /
`CommandValidator`）、3 个读面 + 1 个命令状态查询、2 个 ThreadLocal（`Caller` /
`ScopeContext`）以及「异步边界必须手动传播」的隐式约束、6 个类型表达行范围、8 个类型
表达字段。

这不是业务框架，是框架作者的内部模型。业务开发者需要的是「声明实体 → 写 handler →
写 policy」三步。

### 4.5 测试故事

HR 的 4 个测试全是 `@SpringBootTest`；连「离职后不能改岗位」这种纯业务规则，也要启动
Spring + MockMvc。唯一的纯 JUnit 示例在 `seedj-it/HrEmployeeSampleTest`，光手工装配
`CommandRuntime`、`ReadGateway`、`CommandRegistration`、schema、redactor 就 50+ 行。
反观 advanced 的事件溯源 demo：`Employee.onboard()` 是纯函数，聚合可直接单测。
默认路径比 advanced 路径更难测，这是倒置。

### 4.6 文档税

59 篇文档、14,246 行。新人写第一个命令至少需要读：quickstart → biz/README →
command-contract → read-model → security → errors → testing → decision-guide → 2-3 篇 ADR，
还没算 SPI 宪法与 assessment。文档质量高，但缺少一篇「业务开发者 cookbook」：从零到一个
可上线聚合，包含字段变更、查询、后台任务、错误处理、测试。现在的文档是设计文档，不是
使用手册。

## 5. 架构整洁程度：B-

### 5.1 机械整洁（A-）

无跨模块 split package（allowlist 为空）；无包级环（只保留 parent↔spi 白名单）；
无 >600 行主源码文件（allowlist 为空）；575 个 public 类型全部登记；无 TODO/FIXME；
每个模块有 `package-info.java`；`core` 确实零 Spring / JDBC（ArchUnit + `CorePurityTest`）。

### 5.2 语义整洁（C+）

| 问题 | 证据 |
|---|---|
| 一个模块两个根包 | `seedj-biz-starter` 同时有 `cn.byteforce.seedj.biz.*`（19 文件）和 `cn.byteforce.seedj.starter.*`；ADR-0009 规定 starter 层是 `cn.byteforce.seedj.starter.<area>` |
| `core.core` 命名 | `cn.byteforce.seedj.core.clock` / `.core.security` / `.core.utils`，模块名与包名嵌套 |
| 宿主里的样板基础设施 | `seedj-server/hr` 里 3 个 `LocalDev*` 类（159 行），每个宿主都要抄 |
| 库的测试源码里放完整业务样例 | `seedj-biz-starter/src/test/java/cn/byteforce/seedj/demo/hr`，18 个文件的事件溯源 demo，业务开发者看不到，却占用库的测试维护面 |
| 测试 fixture 与生产 schema 漂移 | `HrEmployeeTestSchema` 缺 `phone`，缺 `indexed/sortable`；声称的 conformance 测试不存在 |
| 重复工具函数 | `snakeCase` 在 `ObjectModules` 与 `ViewMapper` 各一份；`intParam/direction/instant` 在 `ObjectQueryController` 与 `QueryEndpointAutoConfiguration` 各一份 |
| 重复的 `org_units` 字面量 | `HrEmployeeConfiguration.hrOrgUnitContextWriter`、`HrEmployeeCreateHandler.firstOrgUnit` 各读一次；`HrScopeKeys` 里是 `"org.units"` |
| 未接线的 public API | `ObjectSchema.validate()`（仅测试调用）、`requiredFields()`（零调用）、`ObjectModules.creates()/emits()`（运行时零读取） |
| 兼容构造器残留 | `CommandRuntime.Options` 仍有 9 参、10 参两个「兼容既有装配」构造器；ADR-0009 说只保留 9 参全参与 `forTests` |
| 文档与代码不一致 | `decision-guide` 的共享能力声明、`HrEmployeeTestSchema` 的 conformance 声明、ADR-0009 的构造器声明、root pom 里已不存在的 `web-core` / `common/core` |
| Javadoc 通胀 | 每个 `@param` 都写，大量同义反复；`ObjectModules` 里 4 个「回到 owner」的方法各写 4 段 javadoc |
| Lombok 只用了 2 个文件 | 全局 `annotationProcessorPaths` 却一直带着 Lombok |

### 5.3 一个具体的反例

`HrEmployeeModule` 是「声明」，同时是 Spring `@Configuration`，构造器注入两个 handler +
redactor。于是声明无法脱离 Spring 复用 → 测试只能复制一份 schema → 复制件已经漂移 →
漂移没有被任何测试发现 → 而 `ObjectSchema.validate()` 本可在 append 时兜底，却没接线。

这一个小闭环同时暴露了「声明与装配耦合」「单一来源失效」「元数据不闭环」「测试替身漂移」
四个问题。它不是孤例，而是当前架构的缩影。

## 6. 行动清单

### P0：1.0 之前

| # | 动作 | 验收证据 |
|---|---|---|
| 1 | 砍掉或隔离第二写模型：event-sourced 移到 `seedj-advanced` / `examples`，从 `core` public API 移除；若保留，补齐幂等/权限/脱敏/outbox，并修正 `decision-guide` | `public-api.txt` 减少；决策指南不再声称共享；或两条路径各有等价集成测试 |
| 2 | 补自定义查询 seam：`ReadGateway.customQuery(objectType, QuerySpec)`，入参带 `ScopePredicate`、字段策略、审计 | 至少一个 join + 一个聚合示例，且走同一套权限/脱敏/审计 |
| 3 | 冻结迁移：V1 DDL 作为资源/常量冻结；新字段用 V2；补 V1→V2 升级的 Testcontainers 测试 | 加字段后已有库能启动；`seedj_schema_history` 有 V2；checksum 稳定 |
| 4 | 让 schema 成为规范源：append 前调用 `ObjectSchema.validate`；`writeOnly` 自动从 command record / event payload 剔除；`creates`/`emits` 要么删要么强制校验；PG/内存对未知字段行为一致 | 写错字段名 → 400，不是静默丢弃；`emits` 不匹配 → 启动失败 |
| 5 | 声明与 Spring 解耦：`ObjectModuleConfigurer` 用纯类实现，schema 声明可被测试直接复用；删除 `HrEmployeeTestSchema` 或改成从生产声明派生 | 测试直接拿生产 schema；没有第二份字段列表 |
| 6 | 锁的语义修正：`LOCK_BUSY` → 409/429 + `Retry-After`；默认路径加有界重试；评估移除分布式锁（`FOR UPDATE` 已足够） | 并发更新返回可重试错误，不是 503 |

### P1：1.0 之后 3 个月内

| # | 动作 | 理由 |
|---|---|---|
| 7 | `core` 按能力拆 artifact，或至少把 workflow/transit/pki 标为 optional | 业务应用不应被迫携带 575 个类型 |
| 8 | 从 `ObjectModuleRegistry` 生成 OpenAPI，暴露 typed query 端点 | 当前 API 不可发现，手写 yaml 必漂移 |
| 9 | HR 样板改成 cookbook：join 查询、自定义查询、后台消费者（`Caller`/`ScopeContext` 传播）、跨聚合 saga、纯单元测试示例 | 现在的样板只证明能跑，没证明能交付 |
| 10 | CI：conformance 上 PR；`diff-cover` 强制；`dependency-check` 上 PR；容器镜像构建 + 冒烟 | 关键门禁不在合并路径上 |
| 11 | IAM 决策：采购 Keycloak 或把覆盖率提到 85% + 通过 RFC 一致性套件 | 53% 覆盖率的自研 OIDC 是不可接受的安全风险 |
| 12 | 读路径性能：keyset 分页、`total` 可选、1M 行基准；评估 `DISTINCT ON` 查询形态 | 当前 `COUNT(*)` + 子查询在真实数据量下会崩 |

### P2：持续治理

| # | 动作 |
|---|---|
| 13 | 砍 public API 30-40%：`ObjectSchema.validate/requiredFields` 要么接线要么删；`CommandRuntime.Options` 兼容构造器删；`creates/emits` 删 |
| 14 | 统一 Jackson 命名策略，去掉逐字段 `@JsonProperty`；统一 `biz.*` / `starter.*` 包根 |
| 15 | 一篇「业务开发者 cookbook」+ 术语表（DDD 术语 ↔ 实际机制），替代「读 10 篇设计文档」 |
| 16 | 对 `Sealing`、`CommandRuntime`、`ReadGateway` 做 mutation testing |
| 17 | 清理 javadoc 通胀与 stale 注释（`web-core`、`common/core`） |

## 7. 如果只做三件事

1. **把「双时间轴内核」做成真正的产品，把其余能力降级为可选。** `Sealing` /
   `TemporalDdl` / `ReadGateway` / `CommandRuntime` / 错误模型是差异化资产；IAM、workflow、
   eventbus、transit 是成本中心。1.0 应该卖前者。
2. **补「自定义查询」逃生舱，并让它过安全边界。** 没有它，业务团队第二天就会用
   `JdbcTemplate` 绕过权限/脱敏/审计；有了它，「所有读只经 `ReadGateway`」才不是一句
   会逼人造反的口号。
3. **冻结迁移 + 让 schema 真正成为规范源 + 把声明从 Spring 里解耦。** 这三件事共同决定
   「加一个字段」是 1 处还是 8 处，也是 C2（≤3 文件）能否兑现的前提。

## 8. 最终裁定

| 视角 | 判断 |
|---|---|
| 作为架构师维护的平台内核 | B+/A-：双时间轴、读网关、命令运行时、错误模型、迁移器值得保留 |
| 作为业务开发者的基础设施 | C：抽象过载、字段同步、查询天花板、样板教坏习惯 |
| 作为 1.0 对外交付物 | 未就绪：迁移演进有洞、两套写模型不对等、API 文档不可发现、IAM 覆盖率不足、查询表达力不足 |

一句话：**内核有真东西，产品化没完成。** 当前最该做的不是再加能力，而是删能力、
补逃生舱、把「schema 单一来源」从文档变成运行时事实。

## 9. 证据索引

| 结论 | 可复核位置 |
|---|---|
| 模块与依赖方向 | `pom.xml`、`docs/adr/ADR-0008-module-topology.md` |
| 575 个 public 类型 | `config/public-api.txt` |
| HR 样板规模与 `@Bean` 数 | `seedj-server/hr/src/main/java`、`HrEmployeeConfiguration` |
| 37 个框架类型 / 14 个包 | `seedj-server/hr/src/main/java` 的 `import cn.byteforce.seedj.*` 去重 |
| 两套写模型能力差异 | `core/.../cqrs/CommandAgent.java`、`CommandRuntime.java`、`docs/decision-guide.md` |
| schema 声明与视图 / handler / fixture | `HrEmployeeModule`、`HrEmployeeCreateHandler`、`HrEmployeeView`、`seedj-it/.../HrEmployeeTestSchema` |
| `validate()` / `creates` / `emits` 未接线 | `ObjectSchema`、`ObjectModules`、`CommandRuntime`（全仓 grep） |
| 未知字段在 PG 被丢弃 | `PostgresTemporalStore.insert` 只遍历 `table.columns()` |
| 迁移 checksum 冲突 | `HrEmployeeSchemaCapability` + `JdbcMigrationRunner.migrate/validate` |
| 锁失败映射 503 | `LockException extends InfrastructureException`、`ContractErrorHandler` |
| 查询表达力 | `ReadGateway`、`TemporalSearch`、`docs/read-model.md` |
| workflow 零消费者 | 全仓 `import cn.byteforce.seedj.workflow` 只在自身与 `SpiSurfaceTest` |
| 门禁与 CI 触发 | `seedj-it/src/test/java/cn/byteforce/seedj/conformance/`、`.github/workflows/ci.yml` |
| 覆盖率 | `docs/conformance/runs/2026-11-06-a-minus.md`、`config/coverage-baseline.json` |
| 重复工具函数 | `ObjectModules.snakeCase` / `ViewMapper.snakeCase`；`ObjectQueryController` / `QueryEndpointAutoConfiguration` 的参数解析 |
