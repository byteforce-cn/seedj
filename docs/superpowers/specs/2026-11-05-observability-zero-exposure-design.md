# 可观测性零暴露设计：审计 / 指标 / 链路追踪

- 状态：待评审
- 日期：2026-11-05
- 关联：ADR-0002（基础设施 SPI 统一）、ADR-0004（outbox 独立）、`docs/spi/README.md`、ADR-0005（标识契约）
- 决策者：seedj 维护者

## 1. 意图

业务开发人员写业务时，**不应该知道**审计、指标、追踪的存在。

- 审计：只写一个 `@Audited(...)` 注解，哈希链、序号、事务、操作人、存储全部由框架完成。
- 指标：业务代码里不出现 `MeterRegistry`、`Counter`、`Timer`。
- 追踪：业务代码里不出现 MDC、`traceparent`、`Tracer`、`Span`。

三条能力都必须在**框架自己已经掌握的调用点**上完成，不允许要求业务代码配合，也不允许把监控框架的依赖带进 `core`。

## 2. 目标 / 非目标

### 目标

| # | 目标 | 可验证的判据 |
|---|---|---|
| G1 | 审计零暴露 | 业务模块的 `src/main` 不 import `cn.byteforce.seedj.audit.*`（注解除外），不出现 `AuditAppender` / `TransactionTemplate` |
| G2 | 审计与命令同生共死 | 命令回滚 → 审计行不存在、序号不消耗；并发命令的序号连续无缺口 |
| G3 | 被拒绝的命令可审计 | 业务拒绝/聚合不存在有独立记录，且**不进入**哈希链 |
| G4 | 指标不撒谎 | 发布指标名与语义一致（`appended` ≠ `delivered`），不把回滚算成功 |
| G5 | 异步链路不断 | 生产端 → outbox → transport → 消费端 → 业务处理，同一个 `traceId`，消费端 span 是发布端 span 的子节点 |
| G6 | 无监控依赖也能启动 | 类路径没有 micrometer / micrometer-tracing 时，装配正常、seam 退化为 noop |
| G7 | 形状可门禁 | 以上各条都有对应的自动化测试，不靠 review 记性 |

### 非目标（本期不做）

- 不给 `IdGenerator` / `TransitProvider` / `PkiProvider` / `LockProvider` 加指标（同样模式，下一批）。
- 不引入 OpenTelemetry SDK / exporter：只依赖 `micrometer-tracing` API，bridge 由宿主应用选择。
- 不做读操作审计、不做前端埋点、不做审计查询 API。
- 不传播 baggage（只传 `traceparent` / `tracestate`）。
- 不做 relay 的 link span（本期只保证 header 不被改写）。

### 反目标（技术上跑通也算失败）

- 业务代码为了“能被观测”而多写一行调用。
- 指标在事务提交前就报成功。
- 注解标了、但没有存储时静默跳过。
- 追踪只让日志对得上，trace 里却没有消费端的 span。

## 3. 现状（已核对）

| 位置 | 已存在 | 缺口 |
|---|---|---|
| `core/audit` | `AuditAppender` / `AuditChain` / `AuditStore` / `CanonicalHasher` / `AuditEntry` | 没有 `AuditException`/`AuditErrorCode`；`TransactionalAuditAppender` 只存在于 javadoc；`@Audited` 不存在 |
| `core/cqrs` | `CommandAgent` 已保证“拦截器在锁与事务之外”“事件与保存在同一事务” | `Options` 是业务自己 new 的，框架注入不进任何钩子 |
| `starter` | `EventBusMetrics`（消费侧）、`OutboxMetrics`（relay）、`@ConditionalOnClass` + noop 的既有写法 | 发布侧、命令侧、审计侧没有指标 |
| `starter` | `DefaultEventPublisher` 在事务内被调用；`EventConsumerContainer.dispatch` 是消费唯一入口 | 没有任何 trace 依赖与传播 |
| `outbox` | `headers` 已落 JSONB；`EventOutboxMapper` 双向透传；`EventBusOutboxSink` 不改写 | 只是没人往 headers 里写 trace 上下文 |
| 适配器 | `PostgresOutboxStore` 等 | 没有任何 `AuditStore` 实现 |

**关键结论**：追踪与指标不需要改 `core`，也不需要新模块；审计必须改 `core`，因为只有 `CommandAgent` 的事务块能保证原子性。

## 4. 方案取舍（已决策：B）

| 方案 | 注解位置 | 写入点 | 事务原子性 | 被拒绝可审计 | 代价 |
|---|---|---|---|---|---|
| A | 领域事件 | `DefaultEventPublisher` | 是 | 否（无事件） | 最小 |
| **B（选定）** | **Command** | **`CommandAgent` 事务块内** | **是** | **是（链外通道）** | 改 `Options` 构造方式，业务装配变一行 |
| C | 应用服务方法 | starter AOP | 否（`CommandAgent` 自持事务且不经代理） | 是 | 语义错误 |

选 B 的理由：命令是“用户意图 + 目标聚合 + 操作人”的唯一完整载体，`aggregateId()` 类型安全，且能在失败路径上产出记录。A 需要从事件里反射猜 `resourceId`，且无法表达拒绝。

C 被否决：本仓库的事务边界在 `CommandAgent` 内部，切面只能落在事务外，会出现“命令回滚、审计已提交”的哈希链。

## 5. 设计

### 5.1 审计

#### 5.1.1 业务可见面（全部）

```java
@Audited(action = "employee.onboarded", resourceType = "employee")
public record OnboardEmployeeCommand(EmployeeId employeeId, String employeeNo, String name,
                                     String department, String position, LocalDate hireDate)
        implements Command<EmployeeId> {
}
```

注解只有两个属性，均可从命令类推导：

| 属性 | 必填 | 缺省推导 |
|---|---|---|
| `action` | 是 | —（保持显式：动作名进哈希链，不能随类名漂移） |
| `resourceType` | 否 | 命令类简单名去掉 `Command` 后缀并转小写 |

**`resourceId` 恒等于 `command.aggregateId()`**，由 `CommandAgent` 已有的 `IdCodec` 编码为字符串——不引入第二个转换点（ADR-0005）。

**payload 固定为 `{"command":"<简单类名>","aggregateId":"<编码后 ID>"}`**。不序列化命令字段：避免 PII 进审计库、避免命令 record 演化和规范 JSON 的哈希漂移。

#### 5.1.2 执行点

`CommandAgent` 增加一个非泛型 seam（放 `cn.byteforce.seedj.cqrs`，避免 `audit ↔ cqrs` 包循环）：

```java
public interface CommandAudit {

    /** 在命令事务内、save 与 publish 之后调用。抛异常 = 命令回滚。 */
    void accepted(Command<?> command, String resourceId);

    /** 在事务外调用；实现必须吞掉自身异常，绝不改变命令结果。 */
    void rejected(Command<?> command, String resourceId, RuntimeException error);

    static CommandAudit noop() { /* 空实现，仅供测试 */ }
}
```

`CommandAgent.dispatch` 的改动只有一行：

```java
return options.transactionRunner().inTransaction(() -> {
    T aggregate = loadOrCreate(id, command);
    List<DomainEvent> events = aggregate.peekDomainEvents();
    repository.save(aggregate);
    events.forEach(publisher::publish);
    options.audit().accepted(command, codec.encode(id));   // ← 新增，仍在事务内
    return id;
});
```

放在最后一步的理由：只有前面的保存与发布都成功，审计行才存在；任何一步抛异常，三者一起回滚。

拒绝在 `execute` 的出口处理（此处已在事务之外）：

```java
try {
    return invocation.proceed();
} catch (RuntimeException e) {
    if (isBusinessRejection(e)) {
        options.audit().rejected(command, codec.encode(id), e);
    }
    throw e;
}
```

`isBusinessRejection` 只认 `CqrsException`（`COMMAND_REJECTED` / `AGGREGATE_NOT_FOUND` 等）与 `AggregateNotFoundException`；`LOCK_BUSY`、`CONCURRENCY_CONFLICT`、`AuditException`、存储异常一律不算——它们是技术故障，指标负责，不是审计证据。

#### 5.1.3 事务与拒绝通道

拒绝**不能**进哈希链：`AuditStore` 契约要求“回滚后序号不消耗”，而为一条回滚的命令写链外序号会制造缺口；写进同一事务则会被一起回滚。两种都不对。

所以两条通道，语义分开：

| 通道 | 内容 | 事务 | 失败影响 |
|---|---|---|---|
| 哈希链 `AuditChain` | 已受理的状态变更 | 与命令同一事务 | 失败 → 命令回滚 |
| 安全审计 `IamAuditSink` | 被拒绝 / 未授权的尝试 | 独立、尽力而为 | 失败只记日志 |

复用 `IamAuditSink` 而不新增端口：它已有正确的失败语义（`record` 不得影响调用方）和 `outcome` / `reason` 字段。这是**我的判断**，若后续 IAM 变为可选能力，再把它提升为通用 `SecurityAuditSink`。

#### 5.1.4 装配（starter）

审计默认开启（R4：`seedj.<cap>.enabled` 默认 true）；开启但无法落库时**启动失败**，不静默降级。

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "seedj.audit", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    AuditAppender<String> auditAppender(ObjectProvider<AuditStore<String>> stores,
                                        ObjectProvider<PlatformTransactionManager> transactions,
                                        Clock clock, AuditAnchor anchor,
                                        IdGenerator<String> ids, AuditMetrics metrics) {
        AuditStore<String> store = stores.getIfAvailable();
        if (store == null) {
            throw new AuditException(AuditErrorCode.STORE_MISSING, "...");
        }
        if (transactions.getIfAvailable() == null) {
            throw new AuditException(AuditErrorCode.TX_REQUIRED, "...");
        }
        return new MetricsAuditAppender(
                new TransactionalAuditAppender(new AuditChain<>(store, clock, anchor, ids)),
                metrics);
    }

    @Bean @ConditionalOnMissingBean
    CommandAudit commandAudit(ObjectProvider<AuditAppender<String>> appender,
                              ObjectProvider<IamAuditSink> securityAudit,
                              IdGenerator<String> ids) {
        return new AnnotationCommandAudit(appender.getIfAvailable(), securityAudit, ids);
    }
}
```

- **`enabled=true` 且没有 `AuditStore`（或没有 `PlatformTransactionManager`）→ 启动失败**：`auditAppender` 在 bean 创建时抛 `AuditException(STORE_MISSING)` / `AuditException(TX_REQUIRED)`，异常信息给出三条修法：加存储模块（`domain-jdbc-postgresql` + `DataSource`）、`seedj.audit.provider=memory`（开发）、或显式 `seedj.audit.enabled=false`。
- **启动期不扫描 `@Audited`**：命令是 record、不是 bean，类路径扫描既昂贵又不可靠（测试类、多 jar、`@ConditionalOn` 分支）。因此“启动失败”的判据是**配置**而不是注解——这恰好是“不可能静默”的保证：审计开着就必须有存储。
- **存储选择**（R4 的 `provider` 键）：
  - 缺省 + 有 `DataSource` → `JdbcAuditStore`（`@ConditionalOnBean(DataSource)`）；
  - `seedj.audit.provider=memory` → `InMemoryAuditStore`（仅开发/测试，标注与内存 eventbus 同级）；
  - 都没有 → 启动失败（上一条）。
- `TransactionalAuditAppender`：`PROPAGATION_MANDATORY`（已存在事务就加入，没有就抛 `AuditException(TX_REQUIRED)`）。它是“生产装配强制契约”的实现，补上 `AuditChain` javadoc 里承诺的那一层。
- `MetricsAuditAppender`：**包在事务装饰器外面**（量到的是 append 总耗时），但指标名用 `seedj.audit.append.*` 而不是 `*commit*`——事务提交不在它范围内，不制造“已提交”的假象。
- `AnnotationCommandAudit`：读注解 → 解析 actor → 构造 `AuditAppendRequest<String>` → 调 `AuditAppender`。
- **actor**：`SecurityContextHolder` 的 `Authentication#getName()`；无认证回落 `"system"`，匿名回落 `"anonymous"`；永不为 `null`。
- **`enabled=false`**：`AuditAutoConfiguration` 不生效，`CommandAgents` 注入 `DisabledCommandAudit`——它读取注解，对每个被标注的命令类**只 WARN 一次**“审计已显式关闭”。显式关闭是允许的，但不悄悄发生。
- `AuditProperties`：`seedj.audit.enabled`（默认 true）、`seedj.audit.provider`（`postgres` / `memory`；缺省按 `DataSource` 推断）。

#### 5.1.5 存储适配器（domain-jdbc-postgresql）

新增 `cn.byteforce.seedj.audit.jdbc`：

- `AuditMigrations`：`audit_entry`（`id` / `sequence` / `timestamp` / `actor` / `action` / `resource_type` / `resource_id` / `payload JSONB` / `hash` / `prev_hash` / `signature`）+ `audit_head`（单行：`sequence` / `last_hash`）；`UNIQUE(sequence)`。
- `JdbcAuditStore implements AuditStore<String>`：`nextSequenceAndPrevHash()` = 对 `audit_head` 单行 `SELECT ... FOR UPDATE`，返回 `sequence+1` 与 `last_hash`；`insert` 落规范 JSON；`updateHead` 更新单行。**全部在调用方事务内完成**，适配器不管理事务（符合 `AuditStore` 契约）。
- `AuditSchemaCapability implements InfrastructureCapability`：启动期确保表存在（order 沿用 outbox 的惯例）。
- `domain-infra-memory` 增加 `InMemoryAuditStore`：让 demo 与核心测试不需要 PostgreSQL。

### 5.2 指标

#### 5.2.1 现有 + 新增 seam

| 能力 | seam | 现状 | 本期新增 |
|---|---|---|---|
| 事件消费 | `EventBusMetrics`（starter） | 已有 4 个方法 | — |
| outbox relay | `OutboxMetrics`（outbox starter） | 已有 5 个方法 | `oldestLag(Duration)` |
| 事件发布 | `EventBusMetrics` | — | `published(String eventType, boolean outbox)` / `publishFailed(String eventType)` |
| 命令 | `CommandMetrics`（starter） | — | `executed(String command, String outcome, Duration took)` |
| 审计 | `AuditMetrics`（starter） | — | `appended(String action, String resourceType, boolean ok)` |

所有 seam 都是“接口 + `noop()` + micrometer 实现 + 嵌套 `@ConditionalOnClass` 配置”，与现有 `NoMetricsConfiguration` / `MicrometerMetricsConfiguration` 完全同构。**不引入装饰器 Bean，不改 bean 类型**——`DefaultEventPublisher`、`EventConsumerContainer` 用构造注入，避免上一轮评估里 `@Bean EventPublisher(EventPublisher delegate)` 的自引用与覆盖语义问题。

#### 5.2.2 命名与标签纪律

指标名统一 `seedj.<capability>.<subject>.<unit>`；标签白名单写进 `docs/spi/README.md`：

| 允许 | 禁止 |
|---|---|
| `action` / `resourceType`（注解枚举） | `resourceId` / `aggregateId` |
| `eventType` / `topic`（代码常量） | `eventId` / `partitionKey` |
| `command`（类简单名） | `traceId` / `spanId` |
| `outcome` / `status` / `mode` / `retryable` | `actor` / `tenantId` |

`mode` ∈ `outbox` / `transport`；`outcome` ∈ `accepted` / `rejected` / `conflict` / `lock_busy` / `error`。

#### 5.2.3 热路径

- meter **构造期缓存**在字段里（含按 `eventType` 的 `ConcurrentHashMap`），不在每次调用里 `Timer.builder(...).register(...)`。
- `IdGenerator` 不在本期监控范围；`SnowflakeIdGenerator.id()` 是 `synchronized`，逐次计时会加重临界区。
- 计时只覆盖框架调用点，不覆盖业务方法内部。

#### 5.2.4 语义纪律

- 发布指标叫 `appended`（写入 outbox 即返回），不叫 `published`/`sent`；真正“已投递”是 relay 的 `sent`。
- outbox lag 用 `occurredAt → claimed 时刻` 的差值，在 relay 里算一次，不逐条打点。

### 5.3 链路追踪

#### 5.3.1 载体

`traceparent`（W3C）+ 可选 `tracestate`，写进 `EventMessage.headers` → `OutboxRecord.headers` → JSONB → `EventOutboxMapper` → `EventMessage.headers` → `EventEnvelope.headers`。

- **不传 `x-trace-id`**：只有 traceId 无法构造子 span，也丢采样位。
- **不传 baggage**：headers 会落库并对消费者可见，租户/用户信息不得进。
- 保留键：`traceparent`、`tracestate`；其余 header 原样透传。

`core` 只加一个 `EventMessage.withHeaders(Map)`（record 拷贝助手），不引入任何追踪类型。

#### 5.3.2 四个传播点（全部框架所有）

| 点 | 类 | 动作 |
|---|---|---|
| 发布 | `DefaultEventPublisher.publish(EventMessage)` | `inject` 后重建 `EventMessage`；已有 `traceparent` 不覆盖（逃生舱/跨语言生产者） |
| 入 outbox | （无改动） | header 随 `EventOutboxMapper.toRecord` 落库 |
| 出 outbox | `EventBusOutboxSink` | **不改写** header（新增负向测试） |
| 消费 | `EventConsumerContainer.dispatch` | `extract` → `remoteParent` → `try (Scope)` 覆盖反序列化 + `onEvent` + `ack/retry/deadLetter` |

`pollAll` 不开 span（批量操作），由 `EventBusMetrics.pollFailed` 覆盖。

#### 5.3.3 seam 与依赖

seam 放 `starter`（不污染 `core`）：

```java
public interface TracePropagation {

    Map<String, String> inject(Map<String, String> headers);

    /** 恢复上下文；close() 归还作用域，MDC 由 correlation 装饰器负责。 */
    Scope extract(Map<String, String> headers);

    interface Scope extends AutoCloseable { void close(); }
}
```

- 实现 `MicrometerTracePropagation`：`Propagator.extract` → `tracer.nextSpan().remoteParent(ctx)` → `tracer.withSpan(span)`。
- 依赖：`io.micrometer:micrometer-tracing` **optional**，`@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")`；无依赖 → `TracePropagation.noop()`。
- **不手写 MDC**：`put` 无 `remove` 会让线程池串味；MDC 由 tracing bridge 的 correlation 装饰器在作用域内自动填充，`%X{traceId}` 直接可用。

#### 5.3.4 采样

采样位随 `traceparent` 传递；消费端 `remoteParent` 继承父采样决策，不重新采样。outbox 延迟投递不改变采样结果。

### 5.4 模块与依赖方向

```
core            @Audited, CommandAudit, AuditException/AuditErrorCode,
                Options.audit, EventMessage.withHeaders        （零 Spring / 零 micrometer）
   ↑
domain-infra-memory   InMemoryAuditStore
domain-jdbc-postgresql JdbcAuditStore + AuditMigrations + AuditSchemaCapability
   ↑
starter         CommandAgents, AnnotationCommandAudit, AuditAutoConfiguration,
                TransactionalAuditAppender, MetricsAuditAppender,
                CommandMetrics(+Interceptor), AuditMetrics,
                TracePropagation(+Micrometer impl),
                DefaultEventPublisher / EventConsumerContainer 改造
seedj-outbox-starter   OutboxMetrics.oldestLag
```

不新增模块：`starter` 已经是“装配 + 指标/序列化适配”的既有归属（`docs/spi/README.md` §2）。原评估文档提的 `eis-observability` 模块在本仓库没有对应位置。

### 5.5 一次命令的完整时序

```
HTTP / 调度 / 消费  (span 由宿主应用决定)
  └─ CommandAgent.execute
       ├─ interceptors（锁外、事务外）: CommandMetricsInterceptor
       │    └─ dispatch
       │         ├─ lock.acquire
       │         ├─ tx.begin
       │         │    ├─ loadOrCreate + handle
       │         │    ├─ repository.save
       │         │    ├─ eventPublisher.publish → (trace.inject) → outbox.append
       │         │    └─ commandAudit.accepted → AuditChain.append（同一事务）
       │         ├─ tx.commit
       │         └─ lock.release
       └─ catch: commandAudit.rejected（事务外 → IamAuditSink）
  └─ OutboxRelay（另一线程，另一 trace）→ sink.deliver(header 原样) → transport
  └─ EventConsumerContainer.dispatch（另一线程）
       └─ trace.extract(traceparent) → try(scope) → 业务 onEvent → ack
```

## 6. 不变量与失败模式

| 场景 | 行为 |
|---|---|
| 命令回滚 | 审计行不存在、序号不消耗、事件不投递 |
| 审计写失败 | 命令回滚，`AuditException` 上抛（不吞） |
| `enabled=true` 但没有 `AuditStore` | **启动失败**，异常信息给出“加存储模块”或“显式关闭”两条修法 |
| `enabled=false` 但命令标了 `@Audited` | 启动正常；每个命令类首次执行时 WARN 一次 |
| 类路径没有 micrometer | 所有指标/追踪 seam 为 noop，装配正常 |
| 消费端处理抛异常 | span 与 MDC 在 `finally` 内关闭；重试/死信仍在 span 内 |
| 业务自建 `EventMessage` 并自带 `traceparent` | 不覆盖 |
| 业务读 `EventEnvelope.headers()` | 能看到 `traceparent`，文档标注为保留键 |

## 7. 测试与门禁

| 层 | 用例 |
|---|---|
| core 单测 | `CommandAgentAuditTest`：`accepted` 在事务内被调用；命令抛异常时不调用；`rejected` 只在业务拒绝时调用 |
| core 单测 | `AuditedAnnotationTest`：`resourceType` 缺省推导；注解缺失时不审计 |
| 形状门禁 | `CorePurityTest`：`core/src/main` 不含 `io.micrometer` / `io.opentelemetry` / `org.springframework` |
| 形状门禁 | `OptionsDefaultsUsageTest`：`Options.defaults()` 只出现在 `src/test` |
| 装配 | `ApplicationContextRunner`：无 micrometer；**无 `AuditStore` 且 enabled=true → 启动失败**（断言错误码 `STORE_MISSING`）；`provider=memory` 装配成功；`enabled=false` → 无 `CommandAudit` bean |
| 指标 | `SimpleMeterRegistry` 断言 `seedj.audit.append.*` / `seedj.command.execute.*` 的 tag 集合 |
| 集成（PG） | 命令成功 → 同事务审计行；命令回滚 → 无行且序号不跳；并发命令 → 序号连续 |
| 集成（PG） | `traceparent` 经 outbox 往返不变；`EventBusOutboxSink` 不改写 |
| 集成 | `SimpleTracer` 断言消费端 span 的 `traceId` 等于发布端、`parentId` 等于发布端 spanId |
| 负向 | 没有 tracing 依赖时 `DefaultEventPublisher` 仍可发布 |

覆盖率：`starter` 的 70% 行覆盖门禁对本设计的每个新类都适用。

## 8. 迁移

| 文件 | 改动 |
|---|---|
| `core/.../cqrs/CommandAgent.java` | `Options` 增加 `CommandAudit audit`；`dispatch` 调 `accepted`；`execute` 调 `rejected` |
| `core/.../cqrs/CommandAudit.java` | 新增 |
| `core/.../audit/Audited.java` | 新增 |
| `core/.../audit/AuditException.java` / `AuditErrorCode.java` | 新增（R5） |
| `core/.../eventbus/EventMessage.java` | 新增 `withHeaders(Map)` |
| `core/.../SpiSurfaceTest` | 登记 `@Audited`、`CommandAudit`、`AuditException`、`AuditErrorCode` |
| `starter/.../HrConfiguration.java` | 改用 `CommandAgents.options()` 代替 `Options.defaults().with...` |
| `starter/.../DefaultEventPublisher.java` | 构造注入 `EventBusMetrics` + `TracePropagation` |
| `starter/.../EventConsumerContainer.java` | `dispatch` 包一层 `TracePropagation.Scope` |
| `seedj-outbox-starter/.../OutboxMetrics.java` | 新增 `oldestLag(Duration)`；`OutboxRelay` 在 claim 后调用 |
| `starter` 的整上下文测试（`SeedjIT` / `EventBusIT` 等） | 有 `DataSource` 的走 JDBC store；无 `DataSource` 的上下文必须设 `seedj.audit.provider=memory` 或 `seedj.audit.enabled=false`，否则按新规则启动失败 |
| `docs/spi/README.md` | 配置速查补 `seedj.audit.*`；标签白名单（§5.2.2）写入“评审以此为准” |

`@Audited` 是新增注解，对既有业务无影响；`Options` 的 canonical 构造器变化只影响 demo 与 core 测试（`1.0.0` 未发布，无兼容负担）。

## 9. 留给实现者的判断

以下是我已经做掉的判断，实现时可按证据推翻：

1. `CommandAudit` 用 `String resourceId` 而不是泛型 `ID`：ID 编码的唯一权威是 `IdCodec`，而 `AuditChain<String>` 是唯一实现。
2. payload 只放命令名与聚合 ID，不放命令字段：避免 PII 与哈希漂移。
3. 拒绝复用 `IamAuditSink`，不新增端口。
4. 审计指标量 `append` 不量 `commit`：事务提交不在装饰器范围内。
5. 追踪 seam 放 `starter`，不放 `core`。
6. 不新增模块。
7. “启动失败”的判据是配置（enabled + 无 store），不是启动期扫描 `@Audited`：命令不是 bean，扫描不可靠；用配置保证“开着就必须落库”，再用 `DisabledCommandAudit` 的 WARN 覆盖显式关闭的情形。

## 10. 实施顺序与验证

每一步独立可发布，前一步的测试是后一步的前提：

1. `core` 契约 + 形状门禁（`CorePurityTest`、`OptionsDefaultsUsageTest`、`SpiSurfaceTest`）
2. 指标（无新依赖）：`EventBusMetrics` 扩展、`CommandMetrics`、`AuditMetrics`、`OutboxMetrics.oldestLag`
3. 审计：`AuditAutoConfiguration` + `JdbcAuditStore` + PG 集成测试 + demo 迁移
4. 追踪：`TracePropagation` + optional 依赖 + 往返集成测试

```bash
mvn -o -pl core -am verify
mvn -o -pl starter -am verify
mvn -o -pl domain-jdbc-postgresql -am verify
mvn -Panalysis verify
```
