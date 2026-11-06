# seedj2 架构整改目标（A-）

- 日期：2026-11-06
- 状态：verified-with-gaps（2026-11-06）：实现与门禁已完成；A- 未授予，E1/E2 覆盖率未达标，E9/E10 未在 CI 实跑。验收证据见 `docs/conformance/runs/2026-11-06-a-minus.md`。
- 关联评估：2026-11-06 架构评估（本文件第 1 节为结论摘要）
- 目标版本：`1.0.0-SNAPSHOT`（1.0.0 尚未发布，破坏性调整在 1.0.0 前完成；所有破坏性变更必须写 ADR 与变更清单）

## 1. 当前评级与问题摘要

| 维度 | 当前评级 | 主要扣分项 |
|---|---|---|
| 架构合理性 | B+ | 两套写模型能力不对称；通用 `/commands` 缺命令级授权/校验；读侧只有按 ID 查询；业务数据是 `Map<String,Object>` + 手工表定义 |
| 与业内工程规范差距 | B | 覆盖率门禁假绿；仓库内无 CI；LICENSE/NOTICE 缺失；15 个文档链接失效；HR 样板不可直接运行；OpenAPI 对齐是浅校验 |
| 业务开发人员认知负担 | D | HR 样板绕过 Spring/HTTP/PostgreSQL；加字段需同步 5～6 处；ThreadLocal 传 ID、AtomicLong 工号、非法日期 500；两套写模型无决策指南 |
| 架构整洁程度 | B- | 双栈重复；`core` 305/313 类型为 public；跨模块 split package；IAM 四包环；`core` 混入 PG 实现；`InMemoryWorkflowProvider` 2052 行 |

**整改后的最低目标：四个维度均达到 A-。** A- 不是“全部完美”，而是“业务开发者可以按模板安全交付，工程门禁真实有效，架构边界可验证”。

## 2. A- 验收标准

### 2.1 架构合理性（A-）

| 编号 | 验收项 | 可执行证据 |
|---|---|---|
| A1 | 只有一条业务默认写路径；事件溯源路径明确标记为 advanced，并有决策指南 | `docs/decision-guide.md` 存在；`ArchitectureTest` 断言 `seedj-biz-starter` 不自动暴露事件溯源命令入口；HR 使用默认路径 |
| A2 | 每条命令有 `CommandDescriptor`：类型、对象类型、payload 类型、权限、validator、redactor | `CommandCatalogConformanceTest`：所有注册命令 descriptor 完整；`CommandControllerTest` 覆盖 403/400 |
| A3 | 通用命令入口执行命令级权限判定；无权限返回 403 `EIS-403-001` | `CommandControllerTest.unauthorizedCommandIs403`；`HrEmployeeHttpTest` |
| A4 | 通用读侧支持按对象列表、等值过滤、分页、排序、`as_of`/`recorded_as_of` | `TemporalStoreContractTest.search*`；`ObjectQueryControllerTest.list*`；HR 列表示例 |
| A5 | 业务 schema 单一来源：字段定义驱动表、DDL、事件白名单、redactor、校验 | `ObjectSchemaTest`；`SchemaConformanceTest`；HR 加字段只改 schema + command record |
| A6 | 缺锁/事务/outbox 不允许静默降级；开发模式必须显式开启 | `TemporalCommandAutoConfigurationTest` 断言缺依赖启动失败；`application-local.yml` 显式 `degraded-mode=true` |
| A7 | PostgreSQL 下业务表有迁移路径；多实例锁有生产实现 | `ObjectSchemaCapability`；`PostgresLockProviderContractTest`；`HrServerPostgresIT` |

### 2.2 工程规范（A-）

| 编号 | 验收项 | 可执行证据 |
|---|---|---|
| E1 | 覆盖率门禁真实生效：include 路径正确、阈值非零、变更行覆盖 ≥ 90% | `config/ci/check-coverage.sh`；CI 日志；`diff-cover` 报告 |
| E2 | 模块覆盖率 ratchet：core/starter ≥ 80% line，infra/jdbc ≥ 78%，iam ≥ 75%；branch ≥ 60% | `jacoco:check` 每模块执行；`config/coverage-baseline.json` |
| E3 | 仓库内有 CI 配置，且与本地脚本一致 | `.github/workflows/ci.yml` + `config/ci/verify.sh`；README 写明 |
| E4 | SpotBugs 在 CI 必跑，Medium 及以上零发现（排除项需注释） | `-Panalysis verify` job |
| E5 | 格式统一：Spotless `check` 在 CI 必跑；`.editorconfig` 存在 | `mvn spotless:check` |
| E6 | LICENSE/NOTICE 存在并进入制品 | `unzip -l` 断言；`LicensePresenceTest` |
| E7 | 文档链接零失效 | `DocLinkTest` |
| E8 | OpenAPI/命令目录与代码一致 | `CommandApiConformanceTest` |
| E9 | API 兼容门禁在发布流水线执行；SBOM 随制品归档 | `-Pcompat` / `-Psbom` release job |
| E10 | 依赖漏洞扫描：CVSS ≥ 7 阻断 | `-Pdependency-check` job |

### 2.3 业务开发人员认知负担（A-）

| 编号 | 验收项 | 可执行证据 |
|---|---|---|
| C1 | 新人 15 分钟内按 quickstart 启动 HR 本地服务 | `docs/quickstart.md`；`HrServerLocalTest` |
| C2 | 加字段只改 ≤3 个文件：`ObjectSchema` + command record（必要时 view）；事件/DDL/redactor 自动派生 | `HrEmployeeSchemaTest`；`SchemaConformanceTest` |
| C3 | 加命令只改 ≤3 个文件：command record + agent + descriptor 注册；权限/校验/脱敏由 descriptor 声明 | `HrEmployeeHttpTest` |
| C4 | 加查询只改 ≤3 个文件：view record + query service + controller；分页/过滤/时点由模板提供 | `HrEmployeeQueryServiceTest` |
| C5 | 样板无 ThreadLocal、无 AtomicLong、无手工日期解析 500 | `HrEmployeeCreateAgentTest`；`HrEmployeeHttpTest.invalidDateIs400` |
| C6 | 本地模式无外部依赖；PostgreSQL 模式有 docker-compose 与迁移 | `docker-compose.yml`；`HrServerPostgresIT` |
| C7 | 错误码/HTTP 状态有单一文档，业务拒绝不变成 503/500 | `docs/errors.md`；`ContractErrorHandlerTest` 全矩阵 |

### 2.4 架构整洁程度（A-）

| 编号 | 验收项 | 可执行证据 |
|---|---|---|
| S1 | 无跨模块 split package | `SplitPackageTest` |
| S2 | 无包级环（允许 parent↔spi 白名单） | `PackageCycleTest` |
| S3 | 100% public 顶层类型在 `config/public-api.txt` 登记；新增必须显式更新 | `PublicApiSurfaceTest` |
| S4 | `core` 不依赖 Spring / JDBC / `javax.sql` / PG 实现；Jackson 仅 `provided` 且有 ADR | `ArchitectureTest`；`core/pom.xml` |
| S5 | 无 > 600 行的主源码文件（allowlist 仅限自动生成） | `FileSizeGuardTest` |
| S6 | 删除死代码：`HrEmployeeCreate.NONE`、`CommandRuntime.Options` 兼容构造器、`BaseException`/`BizException`/`RemoteException`、未使用的 `COMMAND_REJECTED` 二义 | `DeadCodeTest`/编译；ADR 记录 |
| S7 | 所有模块有 `package-info.java` 说明职责与依赖方向 | `PackageInfoTest` |

## 3. 目标架构决策

### D1：双时间轴命令是业务默认写路径

`TemporalCommandAgent` + `CommandRuntime` + `/commands` 是业务服务默认路径。事件溯源 `CommandAgent` 保留为 advanced 能力，放在明确命名的包/文档中，不再作为 HR 默认样板；两条路径的权限、错误、审计语义必须在文档中列矩阵。不得让业务开发者在无决策指南的情况下二选一。

### D2：命令级契约由 `CommandDescriptor` 承载

`CommandDescriptor` 是命令的唯一元数据：`type`、`objectType`、`payloadType`、`permission`、`validator`、`redactor`。`CommandCatalog` 在执行前应用 `CommandPolicy`；`CommandController` 不再自己拼装权限/校验。通用线格式保持不变。

### D3：读侧提供通用 list/search + 类型化查询模板

`TemporalStore.search(TemporalSearch)` 支持等值过滤、分页、排序、`as_of`/`recorded_as_of`；`ObjectQueryController` 暴露 `GET /objects/{objectType}`。同时提供 `TemporalQueryRepository<T>`，业务方只写 `map(TemporalRow) -> T`。HR 样板必须同时展示通用列表与类型化查询。

### D4：`ObjectSchema` 是业务字段的单一来源

业务只声明 `ObjectSchema`（字段名、类型、required、writeOnly、eventVisible、filterable）。由它派生 `TemporalTable`、DDL、事件白名单、redactor、校验。禁止在 command record、fields helper、table、test fixture、DDL 之间手工同步同一字段。业务表迁移由 `ObjectSchemaCapability` + 显式 `Migration` 完成；不在运行时自动改表。

### D5：依赖缺失必须 fail-fast

`CommandRuntime.Options` 不允许 null 默认值。生产装配缺 `TransactionRunner` / `LockProvider` / `OutboxWriter` 时启动失败；只有显式 `seedj.command.degraded-mode=true`（仅 local/test）才允许 `direct/disabled/discard`，并打印 WARN 启动横幅。`OutboxWriter.discard()` 的名字必须表达“丢弃”。

### D6：错误模型收敛

HTTP 可见业务错误统一为 `ContractException` + `ContractErrorCode`；技术错误统一为 `InfrastructureException`；事件溯源业务拒绝 `CqrsException(COMMAND_REJECTED)` 映射 400。`BaseException`/`BizException`/`RemoteException` 在 1.0.0 发布前删除；`AggregateNotFoundException` 改为 `ContractException(OBJECT_NOT_FOUND)`。`ContractErrorHandler` 给出完整映射矩阵与测试。

### D7：包命名与模块边界

- `cn.byteforce.seedj.<capability>`：契约，只存在于 `core`。
- `cn.byteforce.seedj.infra.<vendor>.<capability>`：适配器，存在于 infra 模块。
- `cn.byteforce.seedj.starter.<area>`：Spring 装配/Web，存在于 starter。
- `cn.byteforce.seedj.server.<name>`：宿主。

不得有跨模块 split package；不得有包级环（parent↔spi 白名单除外）。`core.clock.spi.pg` 移入 jdbc 模块；`schema.sql` 移入 resources 并由迁移管理。

### D8：工程门禁真实有效

CI 必须存在于仓库；覆盖率 include 必须指向真实包；新增/变更行覆盖 ≥ 90%；SpotBugs/Spotless/ArchUnit/DocLink/PublicApi/FileSize 全部在 CI 执行；LICENSE/NOTICE 存在；OpenAPI 与命令目录有对照测试。

### D9：样板即模板

`seedj-server/hr` 必须做到：`mvn -pl seedj-server/hr spring-boot:run -Dspring-boot.run.profiles=local` 直接可用；有 create/update/list/get + 权限 + 错误矩阵 + 时点读 + PostgreSQL 迁移 + HTTP 集成测试；不再包含 ThreadLocal/AtomicLong/未建表/未测试装配。

## 4. 非目标

- 不做前端/UI。
- 不引入第二套数据库（MySQL/Oracle/Redis）。
- 不重写 IAM 的 OIDC/SCIM 业务；只做包边界、错误映射与测试补齐。
- 不强制删除事件溯源能力；只把它降级为 advanced 并补齐文档/权限/错误语义。
- 不在本整改中实现 workflow 引擎的新功能；只拆 god class、补边界测试。
- 不追求 100% 覆盖率；追求“门禁真实 + 关键路径高覆盖 + 变更行 90%”。

## 5. 约束

- Java 21、Spring Boot 4.1.1、Maven 3.9+。
- 线格式 `/commands/{type}`、`/objects/{type}/{id}`、错误信封 `{code,message,details}` 保持兼容；新增字段只能可选。
- 所有破坏性包移动/类型删除必须在 ADR 与 `docs/conformance/change-list-template.md` 记录。
- 每个任务必须先写失败测试，再实现，再跑模块测试；一个任务一个 commit。
- 新运行时依赖必须写 ADR；测试依赖不受此限。
- 公共 API 变更必须更新 `config/public-api.txt`，否则 `PublicApiSurfaceTest` 失败。
- 安全：生产 profile 不得使用 `test-only-key`、不得默认 `CORS *`、不得无权限执行命令。

## 6. 成功指标

| 指标 | 当前 | A- 目标 |
|---|---|---|
| core line coverage | 52.4% | ≥ 80% |
| core branch coverage | 42.1% | ≥ 60% |
| seedj-biz-starter line | 55.4% | ≥ 80% |
| seedj-biz-starter branch | 46.4% | ≥ 60% |
| infra-local line | 71.5% | ≥ 78% |
| jdbc-postgresql line | 73.3% | ≥ 78% |
| iam-starter line | 未测 | ≥ 75% |
| 变更行覆盖 | 未测 | ≥ 90% |
| 文档失效链接 | 15 | 0 |
| 跨模块 split package | 8+ 组 | 0 |
| 包级环 | 4 | 0（或 parent↔spi 白名单） |
| HR 本地启动 | 失败 | < 30s 成功 |
| HR HTTP 集成测试 | 0 | ≥ 12 个场景 |
| `FileSizeGuard` 违规 | 1（2052 行） | 0 |
| CI job | 0 | 4（verify/conformance/analysis/sbom） |

## 7. 参考

- 评估详情：本文件第 1 节；原始评估记录见任务执行前的评审纪要。
- 现有决策：`docs/adr/ADR-0002`、`ADR-0005`、`ADR-0006`、`ADR-0007`、`ADR-0008`。
- 规范工件：`docs/conformance/api/`、`config/conformance/`。
