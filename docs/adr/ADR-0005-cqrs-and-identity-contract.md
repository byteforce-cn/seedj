# ADR-0005：CQRS 写入模型、标识契约与读模型边界

- 状态：accepted
- 日期：2026-11-06
- 决策者：seedj 基础设施维护者
- 关联：`docs/adr/ADR-0002-infra-spi-constitution.md`、`docs/adr/ADR-0004-outbox-standalone-spi.md`、`docs/adr/ADR-0006-distributed-lock-capability.md`

## 1. 背景

### 1.1 标识契约

ADR-0002 的修订引入了 `IdGenerator<ID>` + `Identified<ID>`，Spring 默认 bean 为 `IdGenerator<String>`。落地后出现三个问题：

- `Identified<ID>` **零多态消费者**：全仓没有一处把 `Identified<?>` 用作参数、返回值或泛型上界；`EventMessage.getId()`、`OutboxRecord.getId()` 零调用点；
- 9 个具体 record 各自 `implements Identified<String>` 并手写 `getId()` 别名，纯样板；
- `Identified` 被 `SpiSurfaceTest` 列入 `cn.byteforce.seedj.core` 的公开白名单，等于对一个无人使用的抽象做永久兼容承诺。

同时，`AbstractEntity.equals` 只比较标识、不比较类型，`Order(1)` 与 `Invoice(1)` 相等——在 CQRS 里两者是两条不同的流。

### 1.2 缺少写入/读取模型的基础设施

`AbstractAggregateRoot` 已经提供 `raise/replay/expectedVersion` 与事件收集，但没有：事件存储端口、按流重放的仓储、标识到流键的规范编码、读模型投影的收敛点。每个业务模块都要自己重写一遍，且写法各不相同。

## 2. 决策

### 2.1 删除 `Identified`，标识类型由泛型参数携带

1. 删除 `cn.byteforce.seedj.core.Identified`；`AbstractEntity`、`AuditEntry` 以及 9 个具体 record 移除 `implements`，删除 9 个 `getId()` 别名。
2. `getId()` 保留在 `AbstractEntity<ID>`（Lombok `@Getter`）与 `AuditEntry<ID>` 上——业务方仍有统一入口，只是不再假装“所有有标识的东西是同一类”。
3. 框架签名一律用类型参数：`AbstractAggregateRoot<ID>`、`Repository<T, ID>`、`EventStore<ID>`、`StreamId<ID>`、`IdCodec<ID>`。核心**不出现** `Identified<?>`，也不出现 `String.valueOf(业务标识)`。
4. `AbstractEntity.equals` 增加类型闸门（`getClass() != other.getClass()`）：标识相同但聚合类型不同必须不等。

### 2.2 消息标识保持 `String`，且与业务标识彻底分离

- `EventMessage.eventId`、`OutboxRecord.messageId`、`EventEnvelope.eventId` 保持 `String`。
- 理由：它们是**消息自身的身份 / 投递幂等键**，不是业务实体标识。要求跨语言可读、在 JavaScript 中不丢精度（snowflake 超过 `2^53`）、落库为 `VARCHAR(64) UNIQUE`，且必须只有一种 canonical 形态。
- 业务聚合标识不受影响：`Long` / `UUID` / 业务值对象全程走泛型参数，只在 `IdCodec` 处转一次。
- **拒绝** `EventMessage<T>`：会让 `Long(123)` 与 `String("123")` 成为不可比类型，滚动升级期间消费端去重静默失效，并把 `String.valueOf` 扩散到每个适配器。

### 2.3 `IdCodec` 是唯一的标识转换点

`cn.byteforce.seedj.cqrs.IdCodec<ID>`：`encode(ID) -> String`、`decode(String) -> ID`；必须单射、稳定、可往返，非法文本抛 `CqrsException(INVALID_ID)`。内置 `IdCodecs.strings()/longs()/uuids()`。适配器不得各自写 `toString` / `Long.parseLong`。

### 2.4 写入模型：`EventStore` + `EventSourcedRepository`

- `cqrs.spi.EventStore<ID>`：`read(StreamId)` 返回全量历史；`append(StreamId, expectedVersion, events)` 对同一流原子、按期望版本 CAS，冲突抛 `CONCURRENCY_CONFLICT` 且不写入任何事件；空事件列表是 no-op。
- `cqrs.EventStream<ID>`：`version == events.size()`，只表示全量历史（不含快照），不自洽直接构造失败。
- `cqrs.StreamId<ID>`：`aggregateType + id`，类型名不得含 `:`；`canonical(codec)` 是跨进程流键。
- `cqrs.EventSourcedRepository<T, ID>`：`load` = 读流 + `replayAll`，`save` = 按 `expectedVersion` 追加后清空待提交事件。**不内置 `EventPublisher`**：发布由命令模板在同一事务内完成，避免“事件已投递但流回滚”的半个事务。

### 2.4.1 命令模板：`Command<ID>` + `CommandAgent`

- `Command<ID>` **必须携带 `aggregateId()`**，由边界（应用服务 / Controller）用 `IdGenerator` 分配后构建。没有它，模板无法在加载前确定锁键与 CAS 键，也无法阻止一条命令跨多条流；创建型命令“此刻没有 id”是分配策略问题，不是命令的固有属性。
- `CommandAgent<C, T, ID>` 的 `execute` 是 final，顺序固定：
  `拦截器 → acquire(聚合类型:标识) → 事务外 → begin tx → load/create|handle → peek 事件 → save(CAS) → publish → commit → release`。
- 三个静默失败点由顺序消除：先取后存（防丢事件）、先存后发（防半个事务）、commit 后解锁（防锁提前释放）。
- `createsOnEmpty()` 默认 `false`：修改型命令遇到不存在的流直接 `AggregateNotFoundException`，不会静默建出一个新聚合。
- `CONCURRENCY_CONFLICT` 有界重试：重新加载、重新 `handle`，拦截器只执行一次、事件只发布一次。

### 2.4.2 查询模板：`Query<R>` + `QueryAgent<R>`

`Query<R>` 是可执行接口（`R execute()`），不是空标记：读侧没有顺序可保证，模板的产出是“拦截链包住一次真实的读”。`QueryAgent<R>` 一个读模型一个实例，拦截器按读模型配置（缓存键、鉴权粒度不同）。

### 2.4.3 拦截与两个端口

- `CommandInterceptor<ID>` / `QueryInterceptor<R>`：`around(invocation)`，可短路、可改写结果。命令拦截器在**锁与事务之外**执行。
- 分布式锁是**独立能力** `cn.byteforce.seedj.lock`（见 ADR-0006），`cqrs` 只是消费者：`CommandAgent` 依赖 `LockProvider`，不再拥有 `CommandLock`。
- `cqrs.spi.TransactionRunner`：`<R> R inTransaction(Supplier<R>)`。core 不依赖 Spring：内存实现用 `direct()`，starter 提供 `TransactionTemplate` 实现。锁在事务外层获取与释放，这个顺序由模板拥有——业务方写不出“锁在 BEGIN 之后拿、COMMIT 之前放”的错误（本 ADR 第一版实现就犯过，本地测试全绿）。

### 2.5 读取模型：`AbstractProjection`，不重造订阅机制

`cqrs.AbstractProjection<E>` 实现 `EventSubscriber<E>`：topic 由 `@EventType` 推导（不手写字符串），group 取投影名；子类只实现 `projectionName()` 与幂等的 `apply(E)`。进度、租约、死信仍由 `EventConsumerStore` 负责，core 不复制一套。

为此修正了 `EventConsumerContainer.resolveEventType`：原先只读 `getGenericInterfaces()`，类型实参挂在父类上（`abstract class Base<E> implements EventSubscriber<E>`）会被判为无法解析，消费容器启动时直接失败。现在同时解析 `getGenericSuperclass()`，并有单测锁定——core 的 `AbstractProjection<E>` 就依赖这条能力。

### 2.6 形状门禁与演示模块

- `SpiSurfaceTest` 登记 `cn.byteforce.seedj.cqrs`（16 个类型）、`cn.byteforce.seedj.cqrs.spi`（`EventStore`、`TransactionRunner`）与 `cn.byteforce.seedj.lock`（4 个类型，ADR-0006）；`Identified` 从白名单移除。
- `core` 提供 `EventStoreContractTest`（7 个用例，test-jar），`domain-infra-memory` 用 `InMemoryEventStore` 实现并通过。
- 演示模块 `cn.byteforce.seedj.demo.hr`（`@EnableHr` 显式导入）：事件溯源的 `Employee` 聚合、`EmployeeRepository`、`OnboardingAgent`（命令模板）、`HrCommandService`（标识分配边界）、`EmployeeDirectoryProjection`、`HrQueryService` + 两个 `QueryAgent`、REST 端点。**不使用组件扫描**，避免污染宿主应用（尤其 `starter` 测试里的 `HrServer` 扫描路径）。

## 3. 后果

### 3.1 破坏性变更

- `Identified` 删除；`EventMessage` / `OutboxRecord` / `Iam*` 的 `getId()` 删除（零调用点）。
- `AbstractEntity` 判等更严格：跨类型同标识不再相等。若历史代码依赖旧行为，会在测试期暴露。
- `AbstractAggregateRoot.replayAll` 从包私有改为 public，供 `EventSourcedRepository` 调用。

### 3.2 约束

- 聚合要能被事件溯源加载，必须提供 `blank(ID)` 工厂与 `apply(X)`；`streamType()` 必须是稳定逻辑名，不能依赖类名。
- 投影必须幂等（at-least-once）：`InMemoryEventStore` 与投影表都按标识整体覆盖。
- 实现 `EventStore` 的适配器必须通过 `EventStoreContractTest`；JDBC 实现负责事件序列化与列类型，core 不感知。

### 3.3 未做（明确排除）

- 快照 / 流截断、跟踪型投影进度表、`EventMessage<T>`、`Identified` 的替代接口（`EventId` 值对象）。
- JDBC 事件存储：契约已冻结，实现按契约测试补，不在本 ADR 内。
- **跨流业务不变量**（如“工号唯一”）：CAS 只覆盖单条流，两个不同聚合写同一个工号不会冲突。这类约束必须落在唯一索引或按业务键加锁上，命令模板不提供——演示模块当前允许重复工号，是已知限制。

## 4. 验证

```bash
mvn -o test
```

| 模块 | 用例 |
|---|---|
| core | 110（含 `AbstractEntityTest` 5、`IdContractTest` 5、`CommandAgentTest` 9、`QueryAgentTest` 5、契约测试模板 7） |
| domain-infra-memory | 130（含 `InMemoryEventStoreTest` 7、`InMemoryLockProviderTest` 8、`InMemoryLockAutoConfigurationTest` 3） |
| domain-jdbc-postgresql | 48 |
| workflow-infra-memory | 36 |
| seedj-iam-starter | 13 |
| seedj-outbox-starter | 11 |
| starter | 23（含 `HrOnboardingTest` 8、`HrWiringTest` 3、`EventSubscriberTypeResolutionTest` 3） |

形状门禁：`SpiSurfaceTest` 对 11 个包做公开类型精确匹配；`OutboxPackageIndependenceTest` 保持 outbox 不依赖 eventbus。
