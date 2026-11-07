# 业务开发者 cookbook

本页是**任务手册**，不是设计文档。每个任务写清四件事：改哪些文件、框架自动做什么、
怎么验证、预计多久。示例都以 `seedj-server/hr` 的员工样板为准，可以直接对照源码。

> 先读 [quickstart](./quickstart.md) 把服务跑起来。需要选路时看 [决策指南](./decision-guide.md)；
> 术语不确定时看 [概念与术语](./concepts.md)。

## 任务索引

| # | 任务 | 预计 | 典型改动 |
|---|---|---|---|
| 1 | [新建一个聚合](#1-新建一个聚合) | 30 分钟 | 1 个字段 record + 1 个命令 + 1 个 handler + 1 个模块声明 + V1 |
| 2 | [给已有对象加字段](#2-给已有对象加字段) | 10 分钟 | 字段 record + 命令 record + 一条 V2 |
| 3 | [加一条命令](#3-加一条命令) | 20 分钟 | 命令 record + handler + 声明 + `commands.yaml` |
| 4 | [加列表、过滤与排序](#4-加列表过滤与排序) | 10 分钟 | 字段标注 + 一条 `.query(...)` |
| 5 | [自定义 join / 聚合查询](#5-自定义-join--聚合查询) | 30 分钟 | 一条 reviewed `QuerySpec` |
| 6 | [后台消费者](#6-后台消费者) | 20 分钟 | 1 个 `EventSubscriber` |
| 7 | [跨聚合 saga](#7-跨聚合-saga) | 设计 | outbox 事件 + 订阅方命令 |
| 8 | [不启 Spring 测业务规则](#8-不启-spring-测业务规则) | 5 分钟 | 1 个 `CommandHarness` 测试 |
| 9 | [错误映射](#9-错误映射) | 10 分钟 | 抛 `ContractException` |
| 10 | [迁移升级](#10-迁移升级) | 20 分钟 | 冻结 V1 + 审阅 V2 + 两个测试 |
| 11 | [事件溯源命令](#11-事件溯源命令) | 30 分钟 | 1 个聚合 + agent + `.agent(...)` 声明 |
| 12 | [生产事件存储（PostgreSQL）](#12-生产事件存储postgresql) | 10 分钟 | 1 个 `PostgresEventStore` bean + 启动迁移 |

---

## 1. 新建一个聚合

**改哪些文件**

1. 字段 record：`HrEmployeeFields`（`@SeedjObject(objectType, table, view = true)`）。
   字段的唯一来源；`view = true` 会额外生成读视图。
2. 命令 record + handler：`HrEmployeeCreate` + `HrEmployeeCreateHandler`。
   handler 继承 `AbstractCommandHandler`，用 `schemaFields(ctx, command)` 自动映射同名字段。
3. 模块声明：`HrEmployeeModule`，`.object(HrEmployeeFields.schema())`、
   `.readPermission(...)`、`.command(...)`、`.query(HrEmployeeFields.view())`。
4. 迁移：签入 `V1__<table>.sql`（literal SQL，不再运行时生成）。
5. 装配：一个 `@Configuration` 把 handler 与模块声明注册成 bean；业务声明本身不依赖 Spring。

**框架自动做什么**

- `seedj-codegen` 生成 `<Record>Schema`、OpenAPI 片段、DDL 候选与（`view = true` 时）读视图；
- 命令运行时负责幂等、锁、事务、schema 校验、写范围、outbox、审计与 `writeOnly` 剔除；
- `.query(...)` 自动生成列表端点，分页/过滤/排序/时点读取由模板提供。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-server/hr -am test -Dtest='HrEmployee*Test'
```

对照 `HrEmployeeRulesTest`（纯 JUnit）与 `HrEmployeeHttpTest`（HTTP 契约）。

---

## 2. 给已有对象加字段

**改哪些文件**

1. `HrEmployeeFields`：加一个组件，按需标 `required / filterable / sortable / indexed /
   privacy / writeOnly`。
2. 可写命令 record：需要业务写入时加同名组件；handler 不用逐字段 `put`。
3. 迁移：从生成物复制候选到 `V2__...sql`，人工审阅后提交。**不要改 V1。**

**框架自动做什么**

- 命令 record 组件按 `snake_case` 自动映射到 schema；
- 生成的读视图自动包含新字段（`writeOnly` 除外）；
- 事件载荷与命令记录自动剔除 `writeOnly` 与敏感字段；
- 未知字段返回 400，不再由 PostgreSQL 静默丢弃。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-server/hr -am test \
  -Dtest='HrEmployeeSchemaDriftTest,HrEmployeeUpgradeMigrationTest,HrEmployeeFieldsViewTest'
```

`HrEmployeeSchemaDriftTest` 对比声明与实际表；`HrEmployeeUpgradeMigrationTest` 证明 V1 旧行
升级到 V2 后可读可写。

**为什么不再改 handler**：`HrEmployeeCreateHandler` / `HrEmployeeUpdateHandler` 只保留
业务计算（工号、状态、派生散列）与规则，不再枚举字段。

---

## 3. 加一条命令

**改哪些文件**

1. 命令 record（snake_case 由 `@JsonProperty` 或命名策略处理）。
2. handler：实现 `targetId` 与 `decide`，可覆写 `events`。
3. 模块声明：`.command("hr.employee.archive", HrEmployeeArchive.class)`
   `.permission("hr.employee.write")`
   `.creates()`（创建型命令；对象已存在时运行时失败关闭）
   `.emits("hr.employee.archived")`（未声明的事件类型运行时失败关闭）
   `.redactedCommandField("id_number")`（命令专用且不得落命令记录的字段）。
4. `config/conformance/commands.yaml`：登记 type / permission / payloadSchema / objectType /
   eventTypes；`CommandApiConformanceTest` 会对照。

**框架自动做什么**

- 命令级权限默认拒绝（`CommandPolicy`）；
- 幂等键 + 指纹冲突检测；
- 事件与业务写入同一事务，经 outbox 投递；
- `emits` 与 `creates` 从文档约定变成运行时校验。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-it -am test -Dtest=CommandApiConformanceTest
```

再用 `CommandHarness` 写一条规则测试（任务 8）。

---

## 4. 加列表、过滤与排序

**改哪些文件**

1. 字段 record：给字段标 `filterable = true`、`sortable = true`、`indexed = true`。
2. 模块声明：`.query(HrEmployeeFields.view()).path("/api/hr/employees")
   .permission("hr.employee.read").sort("employee_no")`。

**框架自动做什么**

- 生成 `GET /api/hr/employees`，支持等值过滤、单字段排序、分页与时点读取；
- 读网关统一做对象权限、行范围、字段策略、脱敏与读审计；
- 未声明的过滤/排序字段失败关闭。

**边界**：列表查询只做单表等值过滤。需要 `IN`、范围、join 或聚合时走任务 5，不要用
`JdbcTemplate` 绕过网关。

**怎么验证**

```bash
curl -s 'localhost:8080/api/hr/employees?status=active&sort=employee_no&page=1&size=20'
```

---

## 5. 自定义 join / 聚合查询

**改哪些文件**

1. 在业务模块声明 `QuerySpec`（单条 `SELECT`、字段白名单、命名参数、可排序字段）。
2. 通过 `ReadGateway.query(spec, params, sort, direction, page, caller)` 执行。
3. 适配器侧提供 `QueryExecutor`（PostgreSQL 或内存实现）；没有 executor 时返回
   `EIS-503-001`，不会静默降级。

**框架自动做什么**

- 校验单条 SELECT、禁止 `;`/注释、字段与参数白名单；
- 在子查询外层注入行范围谓词，按 schema 做字段脱敏与读审计；
- 分页与 count 由网关统一处理。

**不做什么**：不解析/改写业务 SQL 语义，不生成 join，不开放 `TemporalStore` 直接拼 SQL。
迁移 SQL 与查询 SQL 都必须人工审阅。

**怎么验证**

- 断言返回行只包含白名单字段；
- 用无权限 caller 调用，断言 403；
- 用越界 scope 调用，断言空页而不是泄漏。

---

## 6. 后台消费者

**改哪些文件**

1. 实现 `EventSubscriber<E extends DomainEvent>`，注册为 Spring bean。
2. `subscription()` 返回 `EventSubscription.of("billing", "hr.employee.created")`；
   group 相同的实例竞争消费，不同 group 各收一份。
3. `onEvent` 必须用 `envelope.eventId()` 做幂等：投递语义是 at-least-once。

**框架自动做什么**

- 轮询、并发、ack、重试、死信与指标；
- 可重试异常按 `InfrastructureException.isRetryable()` 退避；耗尽后进死信；
- trace 上下文跨消息传播。

**身份边界**：事件 headers 不携带 `Caller`（会落库且对所有消费者可见）。需要审计主体时，
把业务主体放进事件载荷，由发布方显式声明；不要透传令牌。

**怎么验证**

用 `EventBusIT` 或内存 transport 驱动一轮 `pollAll()`，断言重复投递不产生第二次副作用。

---

## 7. 跨聚合 saga

**推荐形状**

1. 聚合 A 的命令在自己的事务里写业务数据 + outbox 事件；
2. 订阅方收到事件后，用幂等键调用聚合 B 的命令；
3. 每一步都可重试；失败进死信并告警，不做分布式事务。

**不要做**

- 不要在命令 handler 里直接调用另一个聚合的 store；
- 不要假设事件只投递一次；
- 不要把 workflow 引擎当成默认写路径。需要长流程时用 workflow SPI + 本地实现，
  生产适配器走 API 对接。

**怎么验证**

- 重复投递同一 `eventId`，断言聚合 B 只有一次副作用；
- 在订阅方抛可重试异常，断言退避与死信；
- 检查 outbox 与业务数据在同一事务提交。

---

## 8. 不启 Spring 测业务规则

**依赖**：`seedj-infra-local` 的 test-jar 提供 `cn.byteforce.seedj.test.CommandHarness`。

```java
CommandHarness harness = CommandHarness.of(moduleRegistry(), caller)
        .clockAt("2026-10-08T09:00:00Z");
CommandReceipt created = harness.execute("hr.employee.create",
        Map.of("name", "张三", "org_unit_id", "D01"), "rule-1");

assertThat(harness.object("employee", created.locator()).fields())
        .containsEntry("status", "pending_onboarding");
```

**它提供什么**：内存命令/对象存储、固定时钟、caller、写范围、幂等与 schema 校验。
**它不提供什么**：Spring 容器、HTTP、真实数据库；这些留给 `@SpringBootTest` 切片与
Testcontainers。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-server/hr -am test -Dtest=HrEmployeeRulesTest
```

单条规则应在 1 秒内完成。`HrEmployeeStateMachineTest` 的规则已经迁到
`HrEmployeeRulesTest`，HTTP 状态码仍由 `HrEmployeeHttpTest` 覆盖。

---

## 9. 错误映射

**在 handler 里**

```java
throw reject(ContractErrorCode.COMMAND_REJECTED, "terminated employee cannot change org_unit_id");
```

- `INVALID_PAYLOAD` → 400，结构/类型/未知字段；
- `COMMAND_REJECTED` → 400，业务规则拒绝；
- `OBJECT_NOT_FOUND` → 404，当前对象不存在；
- `FORBIDDEN` → 403，权限或写范围；
- 技术故障抛 `InfrastructureException`，由 Web 边界映射为 503 并带 `Retry-After`。

**不要做**：不要返回 `null`、不要吞异常、不要把内部异常消息直接写进响应。
`ContractErrorHandler` 统一生成 `{code,message,details}` 信封并带 `trace_id`。

**怎么验证**：HTTP 测试断言状态码与 `$.code`；纯规则测试断言 `ContractException.code()`。

---

## 10. 迁移升级

**规则**

1. `V1` 是签入的 literal SQL，发布后只读；改字段一律新增 `V2`、`V3`。
2. `seedj-codegen` 在 `target/classes/META-INF/seedj/ddl/<table>-columns.sql` 生成
   `ADD COLUMN IF NOT EXISTS` 候选；人工审阅后合并进新版本。
3. 启动时不再由当前 schema 生成 DDL；checksum 不会因加字段而变化。
4. `seedj_schema_history` 记录版本与 checksum；失败信息会指出需要新版本。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-server/hr -am test \
  -Dtest='HrEmployeeMigrationTest,HrEmployeeSchemaDriftTest,HrEmployeeUpgradeMigrationTest'
```

- `HrEmployeeMigrationTest` 锁定 V1 checksum；
- `HrEmployeeSchemaDriftTest` 对比声明与实际列名/类型；
- `HrEmployeeUpgradeMigrationTest` 从 V1 旧行升级到 V2，验证当前读与时点读。

---

## 11. 事件溯源命令

**什么时候用**：需要从历史事件重建聚合、每次意图都要进事件流、或者读侧要投影追平。
只需要「查任意时点状态」时用默认路径（任务 1），不要为了“以后可能需要”引入事件溯源。

**改哪些文件**

1. 聚合：继承 `AbstractAggregateRoot<ID>`，实现 `EventApplier<事件族>`；命令方法只
   `raise(event)`，状态只在 `apply(...)` 里改。为 scope 可能引用的字段提供访问器
   （`department()` / `getDepartment()`）。
2. 命令 record：实现 `Command<ID>`，提供 `aggregateId()`；记录组件用 `@JsonProperty`
   或统一命名策略映射 snake_case。
3. agent：继承 `CommandAgent<C, T, ID>`，实现 `aggregateType()`、`create()`、
   `handle()`；用 `CommandAgents.options()` 注入锁/事务/审计。
4. 仓储：继承 `EventSourcedRepository<T, ID>`，覆写 `empty(id)` 与 `streamType()`。
5. 模块声明：`.command(...).permission(...).agent(agent).commandField("employee_id")
   .emits("hr.employee.onboarded")`。`handler` 与 `agent` 恰好一个。
6. 装配：`CommandCatalog` 由 `ObjectModuleAutoConfiguration` 从同一个
   `ObjectModuleRegistry` 构建；业务只需要声明 agent 与 `ObjectModuleConfigurer`。

**框架自动做什么**

- 幂等、未知字段、脱敏、写范围、权限、命令记录与默认路径完全一致；
- 锁、事务、聚合加载/保存、outbox 发布、并发冲突重试由 `CommandAgent` 保证；
- 事件白名单（`emits`）与事件可见性失败关闭；
- 接受回执返回 `target_ref` + `version`，`locator` 为空；`event_ids` 由 agent 分配
  UUID，并与 outbox `message_id` 对齐；
- 生产事件存储（任务 12）把事件写入 `event_stream`，重开仓储时自动注册事件类型并重放。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-biz-starter -am test -Dtest='HrOnboardingTest,CommandControllerAdvancedTest'
```

规则测试直接经 `CommandCatalog`，不要调用 `CommandAgent.execute()`（已删除）；
HTTP 测试用 standalone MockMvc，不需要 `@SpringBootTest`。

**不要做**：不要为 advanced 再写一套幂等/脱敏/权限——统一信封已经提供；不要用
`CommandAgent.execute` 绕过 `/commands`；生产请使用任务 12 的 `PostgresEventStore`，
不要用内存实现在重启后仍宣称数据可恢复。

---

## 12. 生产事件存储（PostgreSQL）

**什么时候用**：advanced 路径要跨重启保留事件流、要重放聚合、要和 outbox/命令终态
同事务。内存 `InMemoryEventStore` 只用于本地与测试。

**改哪些文件**

1. 依赖：`seedj-jdbc-postgresql`（starter 已传递依赖）。
2. 迁移：starter 在 `seedj.event-store.enabled=true`（默认）且
   `provider=postgres`（默认）时自动注册 `EventSchemaCapability`，迁移 scope 为
   `event`，启动期建 `event_stream`。
3. 装配一个带类型参数的 bean（每种聚合标识一个）：

```java
@Bean
public EventStore<EmployeeId> employeeEventStore(
        JdbcTemplate jdbc, EmployeeIdCodec codec, EventPayloadSerializer serializer) {
    return new PostgresEventStore<>(jdbc, codec, serializer, new EventTypeRegistry());
}
```

不传 `serializer` 时使用内置 Jackson JSON；生产建议注入应用配置好的
`EventPayloadSerializer`（与 outbox/eventbus 共用同一序列化格式）。

**框架自动做什么**

- `append` 在事务内校验 `MAX(version)`/`COUNT(*)` 与 `expectedVersion`，批量写入；
  并发追加由 `(aggregate_type, stream_key, version)` 唯一约束裁决，输家得到
  `CONCURRENCY_CONFLICT`，不会留下半条流；
- `EventSourcedRepository.load` 读取前把聚合 `apply` 覆盖的事件类型注册进
  `EventTypeRegistry`（sealed 事件族含 permitted 子类型），重启后第一次读取即可解析；
- 版本空洞、未知事件类型、反序列化失败一律 `CORRUPT_STREAM`，不静默跳过；
- 事件流与 outbox、审计、命令终态共享同一个 `CommandAgent` 事务。

**怎么验证**

```bash
./mvnw -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PostgresEventStoreContractTest
./mvnw -o -B -pl seedj-biz-starter -am test -Dtest=HrOnboardingPostgresTest
```

**不要做**：不要用 `Class.forName` 或手写事件类型映射；不要把 `event_stream` 的
`stream_key` 改成裸标识而丢掉 `aggregate_type`（两条流会互相覆盖）；不要在业务代码里
自行 `DELETE` 事件来“回滚”。

---

## 相关文档

- [quickstart](./quickstart.md)
- [决策指南（能力矩阵）](./decision-guide.md)
- [测试与覆盖率](./testing.md)
- [命令契约](./command-contract.md)
- [读模型](./read-model.md)
- [错误码](./errors.md)
