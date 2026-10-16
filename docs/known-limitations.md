# seedj 已知限制（Known Limitations）

> 面向使用者的当前边界说明，按版本更新；与 [`../CHANGELOG.md`](../CHANGELOG.md)、各模块 `README.md` 互为补充。
> 本文件反映发布树 `1.0.0-SNAPSHOT` 的实际状态。

## 1. EIS 运行时边界

### 1.1 授权缺省 fail-closed（`starter-eis-core`）

- **现状**：命令受理的第一道门是 `EisAuthorizer`；
  - 存在 `EisAuthorizer` Bean → 使用应用提供的决策器；
  - 无 Bean 且 `byteforce.eis.core.allow-all-authorizer=false`（默认）→ **应用启动失败**；
  - 无 Bean 且显式置 `true` → 使用内建全放行授权器并启动打 `WARN`。
- **影响**：未注册授权器的应用升级后**无法启动**（这是有意的安全默认值）；直接开启
  `allow-all-authorizer` 会使命令对任何已解析操作人放行。另外，绕过自动配置手工构造
  `new EisCommandRuntime(..., null, ...)` 仍等于全部放行，仅限测试/内部构造
  （`EisCommandRuntime.java:45-46`、`:186`；`EisCommandRuntimeIntegrationTest.java:84-88`）。
- **规避**：注册 `EisAuthorizer` Bean（可桥接到 `starter-eis-authz` 的判定操作面）；
  仅在开发/演示环境使用 `allow-all-authorizer=true`，生产禁止。

### 1.2 EIS 管理与查询端点无内建注解式授权

- **现状**：`/commands/*`（`starter-eis-core`）、`/admin/audit/*`（`starter-eis-audit`）、
  `/resolve` `/check`（`starter-eis-authz`）、`/objects/{type}/{id}` 与
  `/time-travel/objects/*`（`starter-eis-temporal`）由各 Controller 直接暴露；
  EIS starter **不内置** `SecurityFilterChain` / `@PreAuthorize`。命令端点由 `EisAuthorizer`
  （fail-closed）把关，其余端点依赖宿主应用自身的安全链。
- **影响**：若应用未对上述路径限权，这些端点可能对匿名请求可达。
- **规避**：在应用安全链中显式约束上述路径前缀（例如仅允许已认证主体访问 `/admin/audit/**`、
  `/resolve`、`/check`、`/objects/**`、`/time-travel/**`）。

### 1.3 `GET /commands/{id}` 无属主/租户校验

- **现状**：命令终态查询只按命令 ID 查找，未比对当前操作人/租户
  （`EisCommandController.java:82-94`：`runtime.findCommand(commandId)` 后直接返回，无属主断言）。
- **影响**：任意已认证主体只要持有（或猜到）命令 ID，即可读取**任意**命令的终态（含载荷/拒绝原因）；
  多租户场景下存在跨租户读取风险。
- **规避**：在应用层对 `/commands/{id}` 自行加鉴权或属主/租户过滤（如包裹一层受控 Controller 或过滤器），
  或在该端点前接入应用自己的安全链。

## 2. 数据与一致性

### 2.1 `starter-eis-authz` 无 JDBC 存储实现

- **现状**：随模块提供的存储实现只有进程内的 `InMemoryEisAuthzStore`；判定数据不落库。
- **影响**：重启即丢失、多实例间不共享，无法作为生产权威数据源。
- **规避**：自行实现 `EisAuthzStore` Bean（持久化到 PG），或使用远程模式
  （配置 `byteforce.eis.authz.base-url` 指向权威服务）。

### 2.2 `InProcessEisOutboxDispatcher` 仅进程内标记、无 relay/重放

- **现状**：缺省 outbox 分发器只写日志，`afterCommit` 时把 outbox 记录标记为已分发；
  没有跨服务投递通道，也没有失败重放调度。
- **影响**：跨服务事件投递不会真正发生；进程在分发前崩溃的记录只会留在 outbox 表中等待人工处理。
- **规避**：替换 `EisOutboxDispatcher` Bean，接入组织 MQ / 定时 relay 并自行实现重放。

### 2.3 `starter-eis-authz` 属性源仅提供 SPI（未内置 HTTP 拉取实现）

- **现状**：ADR-0004 属性消费面已提供 `EisAuthzAttributeSource` SPI 与属性条件求值
  （主岗 `primary_position.*` vs 任一岗位 `positions[*].*`，含四类 fail-closed 原因码）；
  但随模块**不提供**调用 `attributes.api.yaml` 的 HTTP 拉取实现。
- **影响**：判定权威（IMS）需自行实现该 SPI（拉取人事域快照并映射 fresh/stale/缺失/不可达）。
- **规避**：注册 `EisAuthzAttributeSource` Bean（典型：调用
  `GET /principals/{id}/attributes?as_of=&recorded_as_of=`）；未注入时依赖属性的条件
  fail-closed（`ATTRIBUTE_SOURCE_UNAVAILABLE`），绝不静默放行。

## 3. 依赖与版本

- **现状**：
  - `coord-java-sdk` 钉在 `0.3.1`（Maven Central 已发布；`0.3.0` → `0.3.1` 无类移除，
    `javap` 成员级比对仅新增与 Transit HMAC 旧方法移除：`hmacSign` / `hmacVerify` →
    用途化 `hmacSignWithPurpose` / `hmacVerifyWithPurpose`（ADR-0012；签名须持久化 `keyId`），
    集成镜像已联动 `v0.3.1`；seedj 自身不封装 Transit HMAC）；
  - 全模块版本 `1.0.0-SNAPSHOT`，**尚未发布到 Maven Central**（消费需本地 `install` 或私服）；
  - EIS 集成套件基于 Testcontainers `postgres:16-alpine`（镜像不可用时硬失败，不静默跳过）。
- **影响**：无法从公共仓库直接解析 `cn.byteforce.seedj:*` 构件；coord 系能力对应 SDK 0.3.1 语义；
  直接消费 `TransitClient` HMAC 的下游代码需按 ADR-0012 迁移（seedj 不封装该面）。
- **规避**：首发前先本地/私服 `install`；coord SDK 升级随后续 `MINOR` 版本评估。

## 4. 工具与测试边界

- **现状**：
  - JaCoCo 覆盖率门禁仅覆盖 **common 域**（`cn.byteforce.seedj.core` / `web` / `security` / `data`，行覆盖 ≥ 80%）
    与 **starter 域**（`cn.byteforce.seedj.starter.**`，行覆盖 ≥ 70%）；`codegen` 等包不在门禁内，
    但仍受「有主源码但零测试」守卫约束；
  - SpotBugs（`-Panalysis`，阈值 Medium）的排除面见 `config/spotbugs-exclude.xml`：
    全仓 `EI_EXPOSE_REP` / `EI_EXPOSE_REP2`（Spring 集成契约要求）与既有 `CT_CONSTRUCTOR_THROW` 登记项；
  - 集成套件 `starter/eis-it` 手工构造 `EisCommandRuntime`（`EisCommandRuntimeIntegrationTest.java:84-88`），
    **不经自动配置的 fail-closed 判定**；授权拒绝路径仅由单测
    `EisCommandRuntimeTest.authorizationDeniedIsRecordedWithReasonCode` 覆盖，集成层不验证该分支。
- **影响**：门禁未覆盖的包可能出现覆盖率盲区；SpotBugs 对上述类别的提示被有意抑制。
- **规避**：涉及这些包/类别的改动走代码评审；新增排除须在 `config/spotbugs-exclude.xml` 注明原因并评审。

## 5. Web 与异步上下文

### 5.1 `RequestContext.wrap` 仅快照/还原 ThreadLocal，不还原 MDC

- **现状**：`RequestContext.wrap(Runnable|Callable)` 只捕获并恢复 `RequestContext` 的
  `ThreadLocal`（`RequestContext.java:165-198`）；MDC 由 `TraceContextFilter`（`traceId`）与
  `IdentityContextFilter`（`userId`/`tenantId`/`username`）写入，`wrap` 不做 MDC 的快照/还原。
- **影响**：把任务提交到线程池并包 `wrap(...)` 后，异步线程内的 SLF4J 日志会丢失 `traceId`/`userId` 等 MDC 字段，
  跨线程日志链路断开（业务代码经 `RequestContext` 读取的上下文仍正确，只是日志关联字段缺失）。
- **规避**：需要跨线程日志关联时，在任务内自行把所需 MDC 字段（或整份 `MDC.getCopyOfContextMap()`）
  传出并在异步线程设置/清理；或使用支持上下文传播的执行器/MDC 适配。
