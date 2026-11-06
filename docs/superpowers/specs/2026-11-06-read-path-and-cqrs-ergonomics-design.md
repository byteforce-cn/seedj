# 读路径纵深与 CQRS 开发体验整改设计

- 状态：待批准
- 日期：2026-11-06
- 输入：`Seedj 架构评估`（HEAD `1eb5386`，2026-11-06）、本设计会话中确认的决策
- 范围：读路径授权/隐私、CQRS 开发体验、评估中的 P0 可靠性项
- 兼容性：库未发布，**不保留向前兼容**；可以删兼容层、改公共契约、压迁移历史

---

## 1. 意图

评估的结论是：写路径（`CommandRuntime` + `TemporalStore` + `ObjectSchema`）是深模块，
读路径和业务开发体验是浅的。本设计把这两处拉平：

1. **读路径必须和写路径一样只有一个入口。** 今天业务代码可以直接注入 `TemporalStore`
   自己 `search`，数据范围、字段脱敏、审计全靠自觉。改造后业务拿不到裸 store，
   “带权限的读”是唯一可用的读。
2. **业务开发者只写命令、命令 handler、query。** 今天两个命令要 11 个文件、975 行，
   其中真正的业务只有两个 `append` 和一次字段合并。改造后一个实体一个
   `ObjectModuleConfigurer`，handler 是业务规则的唯一落点，其余由框架派生。

同时纳入评估中的 P0：幂等键作用域、命令状态归属、PENDING 回收、事件幂等键、
fail-safe 配置、业务索引、审计资源类型、文档绿线。租户隔离**明确不做**，
`seedj.biz.tenant-id` 删除。

---

## 2. 方案与取舍

### 2.1 读路径：为什么是“网关 + 受限谓词”

| 方案 | 结论 | 理由 |
|---|---|---|
| **A. 单一 `ReadGateway` + `ScopePredicate` 下推** | **采用** | 与 `CommandRuntime` 对称：一个入口收口权限、范围、字段、审计；谓词是类型化的，两个适配器语义一致，能下推所以分页/`total` 正确 |
| B. 控制器注解 / AOP | 弃用 | 只能管到 HTTP 面，业务内部调用照样绕过；切面拿不到“哪些行”的语义，只能做对象级权限 |
| C. 逐行 PDP 判定（先查再过滤） | 弃用 | N+1 决策；分页与 `total` 必然失真；大列表不可用 |
| D. 业务返回 SQL 片段 | 弃用 | 只有 PostgreSQL 能执行，内存适配器失效；SQL 注入面；契约测试无法覆盖 |

行范围用**受限谓词**而不是任意表达式：`Eq / In / And / Or / Not`，字段必须在
`ObjectSchema` 里声明。业务自定义和策略引擎都只是谓词的贡献者，只能收窄。

### 2.2 上下文载体：为什么不用 `SecurityContextHolder`

| 方案 | 结论 | 理由 |
|---|---|---|
| **A. 自定义 `ScopeContextHolder`（ThreadLocal + scope）** | **采用** | core 零 Spring；语义是“请求事实”而不是“认证结果”；与现有 `CallerPropagation` 同构；异步传播显式可控 |
| B. 写进 `SecurityContextHolder` / `Authentication.details` | 弃用 | `details` 是不透明口袋，Spring 不保证语义；测试必须起安全上下文；`@Async` 传播依赖额外配置；会话/序列化边界不可控 |
| C. 请求作用域 Bean | 弃用 | 只在 Web 线程有效；异步、定时任务、测试都要另造一套 |

### 2.3 字段隐私：为什么是层级 + clearance

| 方案 | 结论 | 理由 |
|---|---|---|
| **A. 字段声明隐私层级，策略引擎给 clearance，渲染为 full/masked/hidden** | **采用** | “看不到”和“看到打码”是同一机制的两个档位；策略可按域、按角色、按密级组合；schema 仍是单一来源 |
| B. 每字段一个权限字符串 | 弃用 | 20 个字段就是 20 个权限；业务无法表达“敏感字段整体降级”；策略组合爆炸 |
| C. 只有 `writeOnly` 开关 | 弃用 | 现状；没有“可读但打码”和“按人分级”的中间态 |

### 2.4 开发体验：为什么是 Configurer + 显式 handler

| 方案 | 结论 | 理由 |
|---|---|---|
| **A. `ObjectModuleConfigurer` 声明 + 显式 `CommandHandler` + 模板基类** | **采用** | 与 Spring Security 的 `SecurityConfigurer` 同构；默认值集中、覆盖点明确；handler 是业务规则的唯一落点；不引入注解扫描的隐式行为 |
| B. 注解 + 自动发现 | 弃用 | 文件更碎，启动顺序与覆盖关系不可见；`descriptor`/`derived` 的注册规则会变成“要知道才会用” |
| C. 代码生成 / archetype 优先 | 暂缓 | 能进一步减少文件，但生成物与手写代码的边界、升级策略都需要单独设计；先把运行时默认做对 |

### 2.5 明确不做的

- **多租户**：不建 tenant 列、不做租户过滤、不加租户声明。删除
  `seedj.biz.tenant-id` 与 `Caller.tenantId` 的配置来源（`Caller` 字段保留为兼容
  IAM 主体快照的占位，但不再参与数据隔离，文档写明“单租户”）。
- **事件溯源生产存储**：`EventStore` 仍是 reference；默认 starter 不装配。
- **workflow**：不注册、不扩展；是否删除另案处理。
- **代码生成 / archetype**：本设计只做运行时模板；生成器作为后续独立项目。
- **浏览器读面**：不提供。

**反目标**（即使能跑也算失败）：

- 任何业务读绕过 `ReadGateway`（包括直接注入 `TemporalStore`）。
- 没有声明 scope 时默认放行。
- 先分页再在内存里过滤权限。
- 业务返回原始 SQL 片段或自行拼接谓词字符串。
- `degraded-mode` / `dev-auth` 在非 local/test 环境可用。
- 事件幂等键使用固定字符串。
- 静态 `JsonMapper` 单例。

---

## 3. 读路径设计

### 3.1 词汇与落点

| 概念 | 类型 | 位置 |
|---|---|---|
| 受限谓词 | `ScopePredicate`（sealed：`All/None/Eq/In/And/Or/Not`） | `core/domain` |
| 谓词求值（内存） | `ScopePredicates.matches(row, predicate)` | `core/domain` |
| 请求事实快照 | `ScopeContext`（不可变、带声明 key） | `core.security` |
| 声明式 key | `ScopeKey<T>` / `ScopeKeyCatalog` | `core.security` |
| 事实写入扩展点 | `ContextWriter` | `core.security` |
| 事实解析扩展点 | `ContextResolver` | `core.security` |
| 请求作用域载体 | `ScopeContextHolder` | `core.security` |
| 行范围贡献者 | `DataScopeResolver` | `core/read` |
| 字段访问结果 | `FieldAccess`（`Action` + `policyId`） | `core/read` |
| 字段策略结果 | `FieldPolicy`（`Map<String, FieldAccess>`） | `core/read` |
| 字段策略贡献者 | `FieldPolicyResolver` | `core/read` |
| 读入口 | `ReadGateway` | `core/read` |
| 读审计 | `ReadAuditSink` / `ReadAccessEvent` | `core/read` |
| 实体声明 | `ObjectModuleConfigurer` / `ObjectModules` | `core/module` |
| 命令 handler | `CommandHandler` / `CommandContext` | `core/cqrs` |

依赖方向：`module → {domain, cqrs, read, security}`，`read → {domain, security}`，
`domain` 不依赖 `read`。starter 只做装配与 HTTP。

### 3.2 请求生命周期

```text
HTTP / @Async / job
  → ScopeContextFilter（认证之后）
      → ContextWriter[]（按 order，写业务事实）
      → ScopeContext 冻结，进入 ScopeContextHolder
  → ReadGateway.find / search / history
      1. 找 ObjectModule；未知类型 → 404
      2. 对象级权限：module.readPermission 必须存在，caller 必须拥有；
         授权在网关内程序化执行，生成的 controller 不带 @PreAuthorize
      3. 行范围：DataScopeResolver[] → ScopePredicate，AND 收窄
      4. 字段策略：FieldPolicyResolver[] → FieldAccess（FULL/MASKED/HIDDEN，取最窄）
      5. TemporalStore（谓词下推）
      6. 字段渲染：HIDDEN 移除，MASKED 打码
      7. 审计：ReadAccessEvent
  → 投影到视图（类型化查询）或直接返回（通用查询）
```

失败关闭：

| 情况 | 结果 |
|---|---|
| 没有 `DataScopeResolver` 支持该类型 | 403 `EIS-403-001`，不查询 |
| resolver 抛异常 | 503 `EIS-503-001`，不降级为“全部可见” |
| 谓词字段不在 schema 中 | 启动期失败（注册时校验）；scope 可用非 filterable 字段，但必须是已声明字段 |
| 字段策略缺失且层级 ≥ SENSITIVE | `HIDDEN` |
| 对象级读权限未声明 | 启动期失败 |
| 审计写入失败 | 读继续，ERROR 日志 + 指标（审计不阻断可用性） |

### 3.3 谓词契约

```java
public sealed interface ScopePredicate {
    record All() implements ScopePredicate {}
    record None() implements ScopePredicate {}
    record Eq(String field, Object value) implements ScopePredicate {}
    record In(String field, List<?> values) implements ScopePredicate {}
    record NotNull(String field) implements ScopePredicate {}
    record And(List<ScopePredicate> parts) implements ScopePredicate {}
    record Or(List<ScopePredicate> parts) implements ScopePredicate {}
    record Not(ScopePredicate part) implements ScopePredicate {}
}
```

规则：

- 字段必须是 `ObjectSchema` 声明过的字段；`Eq/In` 的值按 `FieldType.coerce` 转换。
- `All` 只允许业务显式返回；框架不提供“默认 All”。
- `Not` 的语义是二值逻辑：`Not(p)` 为真当且仅当 `p` 为假；SQL 编译为
  `(p) IS NOT TRUE`，避免 `NULL` 导致的三值逻辑偏差。
- `In` 的值集合上限 1000；超过由 resolver 自己分批或拒绝，框架不生成巨型 SQL。
- 多个贡献者合成：`reduce(All, And)`，即只能收窄。

`TemporalStore` 签名改为显式带谓词，参数不可省略：

```java
Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query,
                           ScopePredicate scope);

Page<TemporalRow> search(TemporalSearch search, ScopePredicate scope);

List<TemporalRow> history(String objectType, UUID id, ScopePredicate scope);
```

`append` 不变。这样“忘记传范围”是编译错误，不是运行期泄漏。

`current(objectType, id)` 保留给写路径（`CommandRuntime` 与 handler），语义是
“写者视角的当前行”，不应用读范围；`ReadGateway` 不得调用它。业务代码拿不到
`TemporalStore`，因此这不是绕过面。`Eq/In` 的值在进入谓词前按
`FieldType.coerce` 转换一次，内存求值与 SQL 编译使用同一个已转换值。

### 3.4 策略引擎接入

策略引擎不替换业务 resolver，而是**追加一个贡献者**。两个用途：

1. **行范围**：把 IAM 策略编译成 `ScopePredicate`。
2. **字段访问**：逐字段判定 `read` / `read.masked`，映射为 `FULL/MASKED/HIDDEN`。

#### 行范围编译规则

对每条启用的 ALLOW 策略：

- 条件的每个操作数必须属于以下之一：
  - `subject.*` → 请求前已知，折叠成常量；
  - `environment.*` → `ScopeContext` 声明 key，折叠成常量；
  - `resource.<field>` → schema 声明字段，保留为谓词变量。
- 条件必须是 `Always / And / Or / Not / Compare / Exists` 可表达的布尔组合；
  `Compare` 仅支持 `EQ/NEQ/IN/NOT_IN/EXISTS`。
- `resource.<field> OP <常量>` 编译为 `Eq/In/Not`；`Exists(resource.<field>)` 编译为
  `NotNull(field)`；两个 `resource` 字段比较（`resource.a = resource.b`）**不支持**，
  注册时拒绝。
- 多条 ALLOW 策略之间是 OR（任一允许即可）。
- DENY 策略编译为 `Not(condition)`，与 ALLOW 结果做 AND（DENY wins）。
- 任何策略在注册期无法编译 → 启动失败，错误信息带 policy id 与操作数。
  不允许“运行期推不下去再退回先查后过滤”。

编译结果示例：

```text
policy: ALLOW read employee WHERE subject.department == resource.org_unit_id
        AND resource.status == "ACTIVE"
→ Eq("org_unit_id", "D01") AND Eq("status", "ACTIVE")
```

常量折叠可能得到 `All`（条件恒真）或 `None`（条件恒假）；两者都是合法结果。
策略优先级只影响“选哪条 ALLOW/DENY”，不改变集合语义：ALLOW 之间是 OR，
DENY 是 AND NOT，最终结果只取决于调用方是否落在并集/补集里。

字段访问判定复用现有 `IamPolicyDecisionPoint`：

```text
action=read         resourceType=employee  resourceId=id_number
resourceAttributes={classification: "SENSITIVE"}
→ allow  ⇒ FULL
action=read.masked  → allow ⇒ MASKED
两者都不允许              ⇒ HIDDEN
```

`DefaultIamPolicyDecisionPoint` 不需要改协议；新增的是编译器和字段解析器，
两者都放在 `seedj-iam-starter`，通过 core 的 SPI 注入。`seedj-biz-starter`
仍然不依赖 `seedj-iam-starter`。

#### 启动期校验

- 策略引用了 `environment.<key>` 但 `ScopeContext` 没有声明该 key → 启动失败。
- 策略引用了 schema 未声明的 `resource.<field>` → 启动失败。
- 策略条件含不支持的算子 / 字段对字段比较 → 启动失败。
- 字段策略引用了未知 `classification` → 启动失败。

### 3.5 字段隐私

`FieldDefinition` 增加：

```java
public record FieldDefinition(String name, FieldType type, boolean required,
                              boolean writeOnly, boolean eventVisible,
                              boolean filterable, boolean sortable, boolean indexed,
                              Privacy privacy, Masker masker) {}
```

`Privacy`：`PUBLIC < INTERNAL < SENSITIVE < RESTRICTED`。
`Masker`：`Object mask(String field, Object value)`；返回同类型值或字符串。
默认提供 `Maskers.last4()`、`Maskers.email()`、`Maskers.fixed("***")`。
`FieldAccess` 是 `record FieldAccess(Action action, String policyId)`，
`Action` 为 `FULL/MASKED/HIDDEN`；`policyId` 供审计，无策略时为 `null`。

默认渲染表（没有策略贡献者时）：

| 层级 | 默认 |
|---|---|
| `PUBLIC` | `FULL` |
| `INTERNAL` | `FULL`（已认证） |
| `SENSITIVE` | `HIDDEN`（策略允许 `read.masked` 才 `MASKED`） |
| `RESTRICTED` | `HIDDEN`（策略允许 `read` 才 `FULL`） |

约束：

- `writeOnly` 与 `privacy` 正交：`writeOnly` 是“永远不出写路径”，优先于任何字段策略。
- `privacy >= SENSITIVE && eventVisible` → schema 构造失败。事件流是给订阅方的，
  敏感字段默认不得进入 outbox；确需进入时必须显式把字段降为 `PUBLIC` 或在
  `CommandEvent` 层做单独脱敏（本设计不提供后者）。
- 打码只发生在 `ReadGateway` 的投影阶段；导出、审计、日志复用同一份
  `FieldPolicy`，不得各写一套。
- `MASKED` 但字段没有 `masker` → 退化为 `HIDDEN`（不做“猜一个打码规则”）。

### 3.6 上下文：Writer / Resolver / Holder

```java
public interface ContextWriter {
    default int order() { return 0; }   // 升序；不用 Spring 的 Ordered
    void write(ContextSink sink, Caller caller);
}

public interface ContextResolver {
    ScopeContext resolve(Caller caller);
}

public final class ScopeContextHolder {
    public static Optional<ScopeContext> current();
    public static Scope open(ScopeContext context);   // try-with-resources
}
```

- 默认 `ContextResolver`：按 `order()` 升序执行所有 `ContextWriter`，写入
  `ContextSink`，最后冻结成 `ScopeContext`。
- `ScopeKey<T>`：`name`（点分小写，如 `org.units`）+ `type`（`STRING/NUMBER/
  BOOLEAN/DATE/LIST<String>/LIST<UUID>`）；`ObjectModuleConfigurer` 用
  `.contextKey(ScopeKeys.ORG_UNITS)` 声明，`ScopeKeyCatalog` 在启动时收集，
  同名 key 类型必须一致。`ScopeContext` 的 key 只能来自该 catalog；策略编译期
  用它校验 `environment.*`。
- `ContextSink` 拒绝重复 key；同一 key 写两次是启动/请求期错误，不做“后者覆盖”。
- 生命周期：`ScopeContextFilter`（`OncePerRequestFilter`）在认证之后、控制器之前
  打开作用域；`finally` 关闭。非 Web 入口（job、消息消费者）显式调用
  `ScopeContextHolder.open`。
- 异步：一个 `RequestContextDecorator` 同时捕获 `Caller` 与 `ScopeContext`，
  替换现有只搬 Caller 的 `TaskDecorator`；`@Async`、`CompletableFuture` 共用它。
  `@Scheduled` 没有请求来源，由任务自己显式 `ScopeContextHolder.open`，不继承
  上一次请求的上下文。
- 失败：writer 抛异常 → 请求 503，holder 关闭，绝不带着半份上下文继续。

### 3.7 读审计

```java
public record ReadAccessEvent(UUID id, String callerId, String objectType,
                              Operation operation, Map<String, Object> filters,
                              List<UUID> rowIds, String scopeDigest,
                              Set<String> policyIds, Instant at) {}

public interface ReadAuditSink {
    void record(ReadAccessEvent event);
}
```

- `find/history` 记录 row id；`search` 记录过滤条件指纹与命中策略，不逐行记录。
- 默认实现：结构化日志 + Micrometer 计数；持久化审计由业务提供 `ReadAuditSink`。
- 拒绝（401/403）走现有 `SecurityAuditSink`，不新增第二套安全审计。
- `scopeDigest` 是谓词规范化 JSON 的 SHA-256，用于回溯“当时用的哪条范围”，
  不落具体行。

### 3.8 HTTP 面

- 类型化查询由 `ObjectModuleConfigurer` 的 `.query(...)` 声明，框架生成端点；
  业务不写 controller。
- 通用 `/objects/**` 默认关闭。开启需同时满足：
  - `seedj.read.generic-surface=enabled`（仅 local/test 默认可开）；
  - 调用方拥有对象读权限；
  - 走同一个 `ReadGateway`（范围与字段策略不降级）。
- 关闭时返回 404（不是 403），避免暴露内部面存在。
- `docs/conformance/api/query.openapi.yaml` 标记为 internal surface；conformance
  运行使用 `local` profile 显式开启，不作为生产契约。

---

## 4. 写路径开发体验

### 4.1 声明：`ObjectModuleConfigurer`

```java
@Configuration(proxyBeanMethods = false)
public class HrEmployeeModule implements ObjectModuleConfigurer {

    private final HrEmployeeCreateHandler create;
    private final HrEmployeeUpdateHandler update;

    public HrEmployeeModule(HrEmployeeCreateHandler create,
                            HrEmployeeUpdateHandler update) {
        this.create = create;
        this.update = update;
    }

    @Override
    public void configure(ObjectModules modules) {
        modules.object("employee", "hr_employee")
                .readPermission("hr.employee.read")
                .field("employee_no", TEXT).required().filterable().sortable().indexed()
                .field("name", TEXT).required()
                .field("status", TEXT).required().filterable()
                .field("hire_date", DATE)
                .field("id_number", TEXT).privacy(SENSITIVE).mask(Maskers.last4())
                .field("org_unit_id", TEXT).filterable().indexed()
                .command("hr.employee.create", HrEmployeeCreate.class)
                    .permission("hr.employee.write")
                    .creates()
                    .handler(create)
                    .emits("hr.employee.created")
                .command("hr.employee.update", HrEmployeeUpdate.class)
                    .permission("hr.employee.write")
                    .handler(update)
                    .emits("hr.employee.updated")
                .query(HrEmployeeView.class)
                    .path("/api/hr/employees")
                    .permission("hr.employee.read")
                    .sort("employee_no");
    }
}
```

框架从这一份声明派生：

- `ObjectSchema` 与物理表映射；
- `CommandDescriptor`（类型、对象、载荷、权限、校验、脱敏）；
- `CommandCatalog` 条目与启动期校验；
- 读端点、视图映射、字段策略；
- `commands.yaml` 不再手工维护：由 `conformance` 测试从 `ObjectModuleRegistry`
  导出并与仓库内快照比对（字段级），不在构建期生成代码。

### 4.2 Handler：单一 seam

```java
public interface CommandHandler<C> {

    UUID targetId(C command);

    AppendRequest decide(C command, CommandContext ctx);

    default List<CommandEvent> events(C command, AppendResult result, CommandContext ctx) {
        return List.of();
    }
}

public record CommandContext(UUID commandId, UUID targetId, Optional<TemporalRow> current,
                             Instant now, UUID actor, Caller caller,
                             ScopePredicate writeScope) {

    public CommandEvent event(String type, Map<String, Object> payload) { ... }
}
```

- `decide` 在锁内、事务内执行，能看到 `current`；业务不变量、状态机、拒绝都放这里。
- `validate` 不单独暴露：需要“锁外快速失败”的命令在 `decide` 里先判断即可；
  运行时只在 `decide` 内执行，语义唯一。
- `ctx.event(...)` 自动分配事件 id 与幂等键 `commandId:type:index`；handler 不再
  手写 key。
- `targetId` 必须是纯函数，且只在幂等检查**之后**调用：运行时先按
  `(issuer, idempotency_key)` 回放，命中就不解码、不调用 `targetId`；未命中才
  解码并调用一次。这样重放不会消耗业务序列。
- `writeScope` 同时约束读和写：`update` 在 `decide` 前用
  `ScopePredicates.matches(current, writeScope)` 验证旧行；`create`/`update`
  在 `decide` 后用同一谓词验证**将要写入的字段**（构造合成行），越界即拒绝。
  这关闭了“创建一条自己看不见的行”的漏洞。
- 基类 `AbstractCommandHandler<C>` 提供 `IService` 式模板方法：
  `requireCurrent()`、`mergeFields(...)`、`field(...)`、`reject(code, reason)`、
  `appendCreate(...)`、`appendCorrection(...)`、`emit(...)`。基类只做动作，
  不替业务做决定。

### 4.3 注册与启动期校验

`ObjectModuleAutoConfiguration` 收集所有 `ObjectModuleConfigurer`，构建
`ObjectModuleRegistry`；`CommandCatalog` 保留为执行面注册表，由该 registry
构造，`CommandDescriptor` 变为内部类型。`ObjectSchemaRegistry` 合并进
`ObjectModuleRegistry` 后删除。启动期校验（任一失败即启动失败）：

- 命令类型、对象类型、query 路径不重复；
- `objectType` 在 registry 中存在；
- 命令权限、对象读权限、query 权限非空；
- 载荷 record 的组件名是 schema 字段的子集（允许命令专用字段显式豁免）；
- handler 的 `targetId` 对 create 型命令返回非空（通过声明 `.creates()` 标记）；
- `emits` 的事件类型与 handler 实际产生的事件一致（测试期校验，运行期兜底）；
- resolver 返回的谓词字段全部在 schema 中；
- 策略编译器可编译全部启用策略（见 §3.4）。

### 4.4 查询声明与投影

- `.query(HrEmployeeView.class)` 默认用缓存反射读取 record 组件，按 snake_case →
  camelCase 匹配字段，并用 `FieldType` 转换；组件缺失对应字段时为 `null`。
  不支持接口投影（避免第二套映射语义）。
- 需要自定义时提供 `.projection((row, ctx) -> ...)` 覆盖。
- 视图只能看到网关渲染后的行：HIDDEN 字段不存在，MASKED 字段已打码。
- 通用查询与类型化查询共用同一个 `ReadGateway`，不产生第二套分页/时点语义。
- `.query` 的 `permission` 省略时继承对象级 `readPermission`；都声明时必须同时满足。
- `TemporalStore` 的“业务拿不到”是结构性约束，不只是约定：store bean 只暴露给
  `read`/`cqrs` 自动配置；ArchUnit 增加规则，业务包（`seedj-server.*`、starter
  中的示例业务包）不得依赖 `TemporalStore`，违反即构建失败。

### 4.5 消失的东西

- `HrEmployeeConfiguration`（193 行装配）；
- `TemporalCommandAgent` 的两个 `append` 重载、deprecated 构造器；
- 手工 `CommandDescriptor` bean；
- `HrEmployeeFields` 的手工字段名与合并逻辑（迁移到 handler 基类 + schema 常量）；
- 手写 `HrEmployeeQueryController` / `HrEmployeeQueryService` / `HrEmployeeView` 映射；
- `ObjectQueryController` 的 1 参构造器与空 `ObjectSchemaRegistry` 默认。

---

## 5. 可靠性 P0

### 5.1 幂等键作用域与命令归属

- `command_record` 唯一约束改为 `UNIQUE (issuer, idempotency_key)`。
- `CommandStore.findByIdempotencyKey(UUID issuer, String key)`；运行时只按
  `(actor, key)` 回放。
- `GET /commands/{id}`：只有 `record.issuer == 当前 actor` 或拥有
  `seedj.command.admin` 权限才返回；否则 404（不区分“存在但无权”，避免枚举）。
- `replayOrConflict` 只在同 issuer 范围内返回 locator/version/event_ids。
- `Caller.tenantId` 不再来自 `seedj.biz.tenant-id`：`AccessTokenRules.toCaller`
  去掉 `tenantId` 参数，字段固定为 `Caller.DEFAULT_TENANT`。它仅作为 IAM 主体
  快照的键（单租户下恒为 `default`），不参与数据过滤、SQL 或谓词。

### 5.2 PENDING 回收

- `command_record` 增加 `lease_until TIMESTAMPTZ(6) NOT NULL`；PENDING 插入时
  写入 `now + seedj.command.pending-lease`（默认 60s）。
- `CommandStore.expirePending(Instant now)` 删除 `status='pending' AND lease_until < now`
  的记录，释放幂等键；返回删除数。
- `CommandReaper`：启动时执行一次 + `@Scheduled(fixedDelayString =
  "${seedj.command.reaper-interval:30s}")`；指标
  `seedj.command.pending.expired`，日志 WARN 带 command id。
- 回收与执行中的命令存在竞争：lease 必须大于 P99 执行时间；若 reaper 删掉了
  仍在执行的 PENDING，事务内 `complete` 更新 0 行 → 回滚并返回 503，客户端重试。
  这是显式的失败，不是静默丢命令。
- lease 到期后同一幂等键可以重试；不会永久 503。

### 5.3 fail-safe 配置

- `seedj.command.degraded-mode=true` 只允许在 `local`/`test` profile；
  其他 profile 启动失败。判断用允许列表，不是“匹配 prod”。
- `seedj.biz.dev-auth.enabled=true` 同上：只允许 `local`/`test`。
- 事件溯源路径（`CommandAgentAutoConfiguration`）缺 `PlatformTransactionManager`
  时与 temporal 对齐：启动失败；只有 degraded-mode 才允许 `TransactionRunner.direct()`。
- 所有“降级”开关统一由 `DegradedModeGuard` 校验，输出明确的启动错误。

### 5.4 事件幂等键

- 删除固定字符串 `"hr.employee.create:hr:1"`。
- `CommandContext.event(...)` 生成 `commandId + ":" + type + ":" + index`；
  `index` 在单次命令内递增。
- `CommandRuntime` 校验同一命令内事件 key 不重复；重复即 500（框架缺陷，
  不是业务错误）。
- 下游按 `event.id()` 或该 key 去重都成立。

### 5.5 业务索引

- `FieldDefinition` 增加 `sortable`、`indexed`；`filterable` 只控制过滤白名单。
- `TemporalDdl` 为 `indexed` 字段生成部分索引：
  `CREATE INDEX idx_<table>_<field> ON <table> (<field>) WHERE superseded_at IS NULL`。
- 当前状态查询（无 `as_of`/`recorded_as_of`）显式加 `superseded_at IS NULL`，
  让部分索引可用；时点查询才扫多版本。
- HR 至少为 `employee_no`、`org_unit_id` 建索引；排序字段必须是 `sortable`。
- `TemporalTable` 携带 `indexedColumns`；PostgreSQL 测试查 `pg_indexes` 断言索引存在，
  内存适配器不适用（其契约测试覆盖谓词语义，不覆盖索引）。

### 5.6 审计资源类型

- `TemporalCommandAudit.accepted(CommandRecord record, String objectType,
  List<CommandEvent> events)`；`objectType` 来自模块声明，不再从命令类型截断。
- `hr.employee.create` → `employee`。

### 5.7 序列化注入

- 删除 `TemporalCommandAgent.JSON`、`CommandRuntime.JSON` 静态单例。
- `CommandRuntime.Options`、`ObjectModuleRegistry`、HTTP 边界统一注入
  `JsonMapper`；测试与生产使用同一个实例。
- starter 从 `ObjectProvider<JsonMapper>` 取，缺失时 `JsonMapper.builder().build()`。

### 5.8 文档绿线

- `README.md` quickstart 仍是坏的：补 `idempotency_key`，删除不存在的
  `employee_id`；`docs/quickstart.md` 已正确，可对照。
- 评估里报告的 10 个失效链接来自交付给架构师的副本（复制时删除了 `docs/assessment`、
  `docs/superpowers`）；当前工作区文档齐全，`DocLinkTest` 已实测通过。本项只要求
  保持绿线，不需要恢复文件。
- 文档中的 `CommandDescriptor` 构造、`object_id`、`Idempotency-Key` header、
  `RequestContext` 等漂移一并修正；以本设计的新 API 为准重写
  `docs/command-contract.md`、`docs/read-model.md`、`docs/security.md`、`core/README.md`。

---

## 6. 模块与包落点

| 模块 | 新增/修改 |
|---|---|
| `core` | `domain`：`ScopePredicate`、`ScopePredicates`、`Privacy`、`Masker`、`FieldDefinition`、`TemporalStore` 签名；`core.security`：`ScopeContext`、`ScopeKey`、`ContextWriter`、`ContextResolver`、`ScopeContextHolder`；`read`：`ReadGateway`、`FieldAccess`、`FieldPolicy`、`DataScopeResolver`、`FieldPolicyResolver`、`ReadAuditSink`；`module`：`ObjectModuleConfigurer`、`ObjectModules`、`ObjectModuleRegistry`；`cqrs`：`CommandHandler`、`CommandContext`、`AbstractCommandHandler` |
| `seedj-infra-local` | `InMemoryTemporalStore` 实现带谓词的 `find/search/history`；谓词求值复用 `ScopePredicates`；`CommandStore` 新签名 |
| `seedj-jdbc-postgresql` | 谓词 → SQL 编译器（参数化、字段白名单、`IN`、`NOT ... IS NOT TRUE`）；`TemporalDdl` 索引；`command_record` 迁移（issuer 唯一键、`lease_until`、reaper 查询）；审计 `objectType` |
| `seedj-biz-starter` | `ScopeContextFilter`、`RequestContextDecorator`、`ObjectModuleAutoConfiguration`、DSL 实现、生成的查询端点、`ReadGateway` 装配、通用查询开关、`DegradedModeGuard`、`CommandReaper` |
| `seedj-iam-starter` | 策略 → `ScopePredicate` 编译器、`IamFieldPolicyResolver`、`ScopeKeyCatalog` 校验 |
| `seedj-server/hr` | 删除 11 个文件中的装配/查询/fields 样板；保留 `HrEmployeeCreate/Update`、两个 handler、`HrEmployeeView`、`HrEmployeeModule`；新增真实状态机规则（见 §8） |
| `seedj-it` | 读路径矩阵测试、策略编译测试、HR 数据范围 e2e |
| `config/public-api.txt` | 按新公共面重写；业务样板类从框架 API 清单移除 |

`core` 的公共类型目标：新增约 25 个，删除/合并约 10 个；本设计不承诺“降到 30–50”，
core 拆分是独立项目（评估 P1），不在本次范围。

---

## 7. 测试与门禁

| 层 | 覆盖 |
|---|---|
| `TemporalStore` 契约测试 | 两个适配器共用：`Eq/In/NotNull/And/Or/Not`、空结果、非法字段、`IN` 上限、`total` 与分页正确性 |
| `ReadGateway` 单测 | 无 resolver → 403；resolver 异常 → 503；多贡献者 AND；字段层级默认表；`writeOnly` 优先；审计事件形状 |
| 策略编译测试 | ALLOW/OR/DENY/NOT、subject/environment 折叠、字段对字段拒绝、未知字段/未知 context key 启动失败 |
| 字段策略测试 | `read`/`read.masked` 两动作、DENY wins、无策略时 `SENSITIVE` 默认 HIDDEN |
| 上下文测试 | writer 顺序、重复 key、异常关闭、`@Async` 传播、非 Web 入口显式 open |
| handler 测试 | `targetId` 纯度、`decide` 锁内可见 current、`ctx.event` key 规则、写侧 scope 拒绝 |
| 启动校验 | 重复类型、未知 objectType、载荷字段越界、缺权限、无法编译的策略 |
| HR e2e | 总部看全部、部门经理看子树、员工只看自己；`id_number` 打码、`salary` 隐藏；越权写被拒 |
| 文档 | `DocLinkTest` 绿；README quickstart 可复制执行 |

门禁：`verify.sh` 全绿（测试 + 覆盖率 + ArchUnit + DocLink + Spotless）；
覆盖率不得低于现状（core 52.8/42.7、biz 60.1/51.5 为下限），新增代码要求
行覆盖 ≥ 80%。`conformance` 与 `dependency-check` 是否进 PR 门禁另案（评估 §2.2），
本次不扩大 CI 范围。

---

## 8. 迁移与实施顺序

库未发布，不做兼容层。建议四个可独立合并的阶段：

1. **契约与适配器**（core + 两个存储）：`ScopePredicate`、`ScopeContext`、
   `TemporalStore` 新签名、谓词编译、字段隐私、`CommandHandler`。
   此阶段结束，旧 API 删除、`config/public-api.txt` 重写。
2. **读路径**（biz-starter + IAM starter + HR 读面）：`ReadGateway`、
   上下文过滤器、策略编译器、字段策略、通用查询开关、HR 查询迁移、e2e。
3. **写路径 DX**（module + cqrs + HR 写面）：`ObjectModuleConfigurer`、注册校验、
   `CommandContext`、handler 基类、HR 两个 handler、删除 193 行装配。
4. **可靠性 P0 与文档**：幂等键作用域、PENDING reaper、fail-safe guard、
   事件 key、索引、审计资源、`JsonMapper`、README/DocLink。

每阶段独立可测；阶段 2 依赖阶段 1 的契约，阶段 3 与阶段 2 只共享 `module` 声明。

**HR 样板补充真实规则**（评估 §1.2.4）：`status` 状态机
`PENDING_ONBOARDING → ACTIVE → TERMINATED`；离职后禁止改岗位/工号；
入职日期不得晚于今天；`org_unit_id` 必须存在。至少一条规则通过
`COMMAND_REJECTED` 在 e2e 中展示。

**迁移历史**：不保留已发布的迁移；本地/演示库直接重建。`HrEmployeeSchemaCapability`
的 V1 按新 schema 重新生成；不再需要 V2 ALTER。`JdbcMigrationRunner` 的 checksum
语义不变。

---

## 9. 已定的实现取舍（builder 可直接执行）

1. `ScopePredicate` 放 `domain`，`ReadGateway` 放 `read`；`domain` 不依赖 `read`。
2. `ContextWriter` 直接写 `ContextSink`，不经过 `SecurityContext`；`ContextResolver`
   默认实现执行 writers，业务通常只实现 writer。
3. 字段访问三档用两个策略动作（`read` / `read.masked`）表达，不改 PDP 协议。
4. 行范围只支持 `Eq/In/NotNull/And/Or/Not`；不支持字段对字段、范围比较、外部调用。
5. `IN` 上限 1000；超过由 resolver 处理。
6. `Not` 编译为 `(p) IS NOT TRUE`；内存求值用二值逻辑。
7. 写侧复用同一个 `ScopePredicate`：`decide` 前对 `current` 求值，`decide` 后对
   “将要写入的字段”构造的合成行求值（`create` 与 `update` 都覆盖）。
8. 通用 `/objects/**` 默认关闭；开启仅 local/test 默认允许，生产需显式配置。
9. 读审计默认结构化日志 + 指标；持久化审计由业务实现 `ReadAuditSink`；
   审计失败不阻断读。
10. handler 必须在 configurer 中以实例传入（configurer 可以是 `@Configuration`，
    自行注入依赖）；框架不做类扫描或 `AutowireCapableBeanFactory` 反射创建。
11. 视图映射默认 snake_case ↔ camelCase；自定义投影用 `.projection(...)`。
12. 命令载荷字段与 schema 的校验默认严格；命令专用字段用 `.commandField(...)`
    显式豁免。
13. PENDING 回收采用“删除过期 PENDING”而不是新增 `EXPIRED` 状态：崩溃的命令
    视为从未发生，幂等键释放，`GET /commands/{id}` 返回 404。
14. 删除 `Caller.tenantId` 的数据含义：`AccessTokenRules.toCaller` 去掉
    `tenantId` 参数，固定 `Caller.DEFAULT_TENANT`；IAM 策略仍按 `default`
    查找。文档、配置、SQL、谓词一律不使用租户；`seedj.biz.tenant-id` 删除。
15. `ContextWriter` 用自定义 `order()`，不引入 Spring `Ordered`；core 保持零 Spring。
16. `ScopeKeyCatalog` 由 `ObjectModuleConfigurer` 声明；策略编译器只接受已声明 key。
17. `ReadGateway` 的公开签名就是 `find(objectType, id, query)`、
    `search(objectType, search)`、`history(objectType, id)`，调用方传入 `Caller`；
    不引入额外 `ReadRequest` 包装类型。
18. `FieldAccess` 带 `policyId`，供审计；`FieldPolicy` 是字段到 `FieldAccess` 的不可变映射。
19. `Masker` 返回 `Object`，允许数字/日期保持类型；默认打码实现只返回字符串。
20. 视图映射用缓存反射，不做构建期代码生成；`commands.yaml` 由 conformance
    测试从注册表导出比对，不手工维护。
21. `ObjectSchemaRegistry` 删除，能力合并进 `ObjectModuleRegistry`；
    `CommandCatalog` 保留为执行面入口。
22. 写侧范围检查覆盖 `create` 与 `update`：对“将要写入的字段”构造合成行做谓词求值。

---

## 10. 验收标准

- 业务代码无法注入 `TemporalStore`（编译期不可见或包级私有）；所有读经
  `ReadGateway`。
- 未声明 resolver 的对象类型读请求返回 403；不存在“默认全部可见”。
- 一个已认证但无权限的用户无法通过 `/objects/**` 或类型化端点读到任何行；
  越权行在分页前被过滤，`total` 正确。
- `phone` 对无 `read.masked` 策略的调用方不可见；对有策略的调用方只显示后四位；
  `id_number_hash` 永远不可读。
- 两个命令的 HR 实体手写文件 ≤ 6 个（module、2 个命令 record、2 个 handler、1 个 view），
  主代码 ≤ 300 行。
- 幂等重放不调用 `targetId`、不消耗业务序列。
- 崩溃后同一幂等键可重试；不存在永久 PENDING。
- 非 local/test profile 下 `degraded-mode` / `dev-auth` 启动失败。
- 事件幂等键随命令 ID 变化；两个不同命令不会产生相同 key。
- README quickstart 可原样执行；`DocLinkTest` 绿。
- `verify.sh` 绿，覆盖率不低于现状。

---

## 11. 未决问题（不阻塞本设计）

1. `core` 拆分（314 个 public 类型）与 workflow 的去留——独立项目。
2. 事件溯源生产存储与快照——独立项目；默认 starter 继续不装配。
3. 代码生成 / archetype——在运行时模板稳定后单独立项。
4. 读审计持久化选型（表 / 事件流 / 外部 SIEM）——由部署方决定。
5. 字段级加密（落库加密）与派生字段的关系——本设计只做读投影打码，不改变存储加密。
