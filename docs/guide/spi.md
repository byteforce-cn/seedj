# seedj 基础设施 SPI 宪法

> 适用于 `pki`、`transit`、`eventbus`、`outbox`、`id`、`workflow` 六个能力，以及未来新增的任何能力。
> 新增能力时必须逐条勾选；评审时以此文档为准。

## 0. 业务方只需知道三件事

```java
// 一个依赖（starter）→ 注入一个接口 → 调一个方法
pki.signingIdentity("iam")                       // 证书
transit.crypto().encrypt(EncryptRequest.of("app-key", "secret"))   // 加密
String id = idGenerator.id()                     // 由 IdGenerator 产生
eventPublisher.publish(new OrderCancelled(id))   // 事件
outboxWriter.append(OutboxRecord.of(idGenerator.id(), "order.cancelled", id.value(),
        "order.cancelled", payload))              // 事务性消息
workflowProvider.start(request)                  // 审批实例
Caller caller = callers.find().orElseThrow()     // 当前调用方（未认证时为空）
```

业务代码**不得** import 任何实现模块（`cn.byteforce.seedj.infra.*` / starter 实现）的类；只需要 `core` 里的契约类型。

## 1. 九条规则

| # | 规则 | 反例（本仓库已修复） |
|---|---|---|
| R1 | **一个能力 = 一个业务入口 +（可选）一个实现入口** | `OutboxProvider` 把 relay 的协调方法暴露给业务方 |
| R2 | **业务入口必须是意图级**：调用方不必理解库内状态机 | 要求业务方填 `sequence` / `leaseId` |
| R3 | **装配只用 Spring 自动配置**：实现模块自带 `@AutoConfiguration`，bean 名即 provider 名；多实现用 `Map<String,T>` + `seedj.<cap>.provider` 选择 | `ServiceLoader` + `BeanFactory.createBean(Class)` 反射 |
| R4 | **每个能力两个配置键**：`seedj.<cap>.enabled`（默认 true）+ `seedj.<cap>.provider`（多实现时必填）；Properties 类随实现模块交付 | 只有 eventbus 有 provider 字符串、pki 有 enabled、transit 什么都没有 |
| R5 | **统一错误模型**：`InfrastructureException(code, category, retryable)`；每能力一个 `ErrorCode` 枚举 | 裸 `IllegalStateException("Lease lost")`、两套嵌套异常 |
| R6 | **统一模型风格**：一类型一文件、`record` 或 `final class`、构造期校验、`toString` 脱敏；禁止 `Ports`/`Models` 容器类 | `Ports.java` / `Models.java` |
| R7 | **统一生命周期**：入口 `extends AutoCloseable`，`close()` 幂等且不抛 | pki 原先没有 `close()` |
| R8 | **每能力三件套测试**：契约 TCK + 装配测试 + 集成测试 | eventbus/transit 只有一个 mock 单测 |
| R9 | **过滤器链顺序**：只有最后一条链可以不写 `securityMatcher`，且必须是 `LOWEST_PRECEDENCE`；其余链必须有 matcher + 唯一 `@Order` | IAM 的 `defaultSecurityFilterChain` 曾是无 matcher 的 `@Order(5)` catch-all，聚合宿主里吃掉 biz 链 |

## 2. 模块与职责

| 模块 | 提供 | 不该出现 |
|---|---|---|
| `core` | 契约（接口 + 请求/结果 + 错误码）、迁移原语、契约测试 TCK（test-jar）、`core/security`（`Caller` / `CallerProvider` / `SecurityAudit*` / `AccessTokenRules`）、`outbox` / `outbox/spi` 契约、`workflow/spi` 工作流契约 | Spring、BouncyCastle、任何存储机制 |
| `seedj-infra-local` | 本地实现：PKI（BC + 状态文件）、Transit（JCE + 状态文件）、EventBus 本地队列（file / memory，含消费者组）、雪花 + 字符串 ID、Lock、Audit memory、IAM memory、本地工作流引擎（v2.4） | outbox、relay、PostgreSQL 存储 |
| `seedj-jdbc-postgresql` | PostgreSQL 实现：Outbox 存储 + 投递装配（`OutboxWriter` / `OutboxRelay` / `OutboxMetrics` / `OutboxInfrastructureInitializer`）、Audit 存储、IAM 存储、版本化迁移 + `InfrastructureCapability`；**允许**自带条件化 `@AutoConfiguration` | 队列 transport、业务逻辑、无条件的 Spring 装配 |
| `seedj-biz-starter` | 业务服务开箱即用：资源服务器安全（`CallerProvider`、401/403 JSON、拒绝审计）、`CommandAgents`、`EventPublisher`、`EventConsumerContainer`、`EventTopicCapability`、`InfrastructureInitializer`、审计/指标/追踪适配 | 业务逻辑；**不得依赖 `seedj-iam-starter`**（`BizStarterPackageIndependenceTest` 门禁） |
| `seedj-iam-starter` | IAM 契约的 Spring 装配：OIDC/SAS、用户存储适配、RBAC/ABAC/TOTP/SCIM/native-auth、IAM schema capability | 业务逻辑；不得反向依赖 `seedj-biz-starter` 或 server |
| `seedj-server-iam` | 可运行 IAM/OIDC 服务；只带 `seedj-iam-starter` + 两个实现模块 | 业务横切能力 |
| `seedj-server-hr` | HR 参考服务：验证 `seedj-biz-starter` + core/infra 的分布式能力 | `seedj-iam-starter` |
| `seedj-server-im` | IM 参考服务：验证长连接（已落地）、扇出、顺序、幂等等 core 能力 | `seedj-iam-starter` |
| `seedj-it` | **集成测试聚合**：三个 server + 夹具；`src/main` 不含代码 | 业务逻辑、存储/传输实现 |

依赖方向：`core` ← `{seedj-infra-local, seedj-jdbc-postgresql}` ← `{seedj-biz-starter, seedj-iam-starter}` ← `seedj-server-*` ← `seedj-it`。
两个 starter 之间零依赖；两个实现模块之间零依赖；`core` 永远零 Spring 依赖；outbox 永远不 import `eventbus`。

> 修订：实现模块可以自带 `@AutoConfiguration`，但必须满足 `@ConditionalOnClass`/`@ConditionalOnBean`/`@ConditionalOnProperty`，并在 `AutoConfiguration.imports` 登记；宿主/`seedj-it` 仍负责最终装配与覆盖。

## 3. 多服务一致性

| 能力 | 多服务共享方式 | 默认路径 |
|---|---|---|
| PKI | 状态文件（签发 + 吊销 + 签名身份） | `${java.io.tmpdir}/seedj-pki-state.properties` |
| Transit | 状态文件（密钥 + 版本历史） | `${java.io.tmpdir}/seedj-transit-state.properties` |
| EventBus 队列 | 目录（每条消息 `.payload` + `.meta`） | `${java.io.tmpdir}/seedj-eventbus` |
| Outbox | PostgreSQL 单表 | `outbox` / `outbox_audit`（scope `outbox`） |
| ID | 节点编号分配文件 | `${java.io.tmpdir}/seedj-id-nodes.properties` |
| Workflow（memory） | 单进程内存，不跨服务共享 | 无（接入持久化 Provider 后再定义） |

同一台机器上启动多个服务时，把上述路径指向同一位置即可共享状态，无需额外协调。

## 4. 配置速查

```yaml
seedj:
  pki:      { enabled: true, ca-resource: classpath:certs/pki-test-ca.p12, ca-password: ..., ca-alias: pki-test-ca, revocation-store: ... }
  transit:  { enabled: true, persist: true, state-file: ... }
  id:       { enabled: true, node-id: 3, epoch-millis: 1704067200000, node-state-file: ... }
  eventbus:
    enabled: true
    transport: file          # file | memory
    file:  { base-dir: ... }
    memory:{ capacity: 10000 }
    topics: [order.cancelled]       # 额外确保存在的队列 topic（订阅 topic 自动加入）
    consumer: { enabled: true, poll-interval: 200ms, batch-size: 100, lease: 30s, max-attempts: 5, retry-base: 1s, retry-max: 300s }
  outbox:
    enabled: true
    provider: postgres
    relay: { enabled: true, poll-interval: 1s, batch-size: 100, lease: 30s, max-concurrency: 20, retry-base: 1s, retry-max: 300s }
    store: { append-max-attempts: 3 }
  audit:
    enabled: true            # 默认 true；开启但无 AuditStore / PlatformTransactionManager 时启动失败
    provider: postgres       # postgres（需 DataSource）| memory（仅开发/测试）
  workflow: { enabled: true, provider: memory }
```

## 4.0 可观测性（审计 / 指标 / 追踪）

业务代码只多一个注解，其余由框架完成：

```java
@Audited(action = "employee.onboarded", resourceType = "employee")
public record OnboardEmployeeCommand(EmployeeId employeeId) implements Command<EmployeeId> {}
```

- 审计：`CommandAgent` 事务内追加哈希链；回滚则审计一并回滚；被拒绝的命令走 `IamAuditSink`，不进链。
- 指标：`seedj.eventbus.*` / `seedj.outbox.*` / `seedj.command.*` / `seedj.audit.*`，业务代码不出现 `MeterRegistry`。
- 追踪：发布时把 `traceparent` 写进 `EventMessage.headers`，消费时恢复上下文；业务代码不出现 MDC/Tracer。

**标签白名单**（评审以此为准）：只允许 `action`、`resourceType`、`eventType`、`topic`、`command`、`outcome`、`status`、`mode`、`retryable`；**禁止** `resourceId`、`aggregateId`、`eventId`、`partitionKey`、`traceId`、`actor`、`tenantId`。

## 4.1 EventBus 消费与消费者组

业务方实现 `EventSubscriber<E extends DomainEvent>` 并注册为 Spring bean：

```java
@Component
public final class OrderCancelledSubscriber implements EventSubscriber<OrderCancelled> {
    public EventSubscription subscription() {
        return EventSubscription.of("billing", "order.cancelled"); // group, topic...
    }
    public void onEvent(EventEnvelope<OrderCancelled> envelope) {
        billingService.onCancelled(envelope.event(), envelope.eventId());
    }
}
```

- **同一 group 内多实例竞争**：一条消息只交给一个实例；单服务多实例部署时使用同一个 group。
- **不同 group 各收一份**：每个 group 独立 ack/retry/死信。
- `group` 留空时回退到 `spring.application.name`，同类实例默认落在同一个组。
- 正常返回 → ack；可重试异常 → 退避重试；不可重试或超过 `max-attempts` → 死信。
- 业务必须用 `eventId` 做幂等（at-least-once）。
- 消费机制由 `EventConsumerStore` 提供：file（同机跨进程）、memory（单进程）、未来 Kafka/RabbitMQ 适配器直接映射原生 group。

## 4.2 资源初始化与迁移

`InfrastructureInitializer`（starter）在启动期按 `order` 执行所有
`InfrastructureCapability`：`isReady → initialize → validate`。required 能力失败阻断启动，
optional 只 WARN。内置能力：

| 能力 | 模块 | 保证的资源 |
|---|---|---|
| `postgres-outbox` | `seedj-jdbc-postgresql` | `outbox` / `outbox_audit` / `seedj_schema_history` |
| `eventbus-topics` | `seedj-biz-starter` | 订阅与配置声明的队列 topic |

迁移不引入 Flyway：`JdbcMigrationRunner` 使用 version + SHA-256 checksum +
`(scope, version)` 主键的 `seedj_schema_history` + PostgreSQL advisory lock，语义与 Flyway 对齐：
已应用版本不重复执行、checksum 变化/数据库存在未知版本时拒绝启动、多实例并发启动只执行一次。
每个能力使用独立 scope（如 `outbox`、`iam`），版本号互不干扰。
`seedj-jdbc-postgresql` 自带 `OutboxInfrastructureInitializer`：只依赖该模块的应用也能执行迁移；宿主自己的 initializer 与其同时存在时靠 `isReady()` 幂等。

## 4.3 工作流（审批）

业务方注入 `WorkflowProvider`，只表达意图（`start` / `act` / `listTasks`），不维护审批表：

```java
WorkflowInstance instance = workflowProvider.start(new StartWorkflowRequest(
    new WorkflowInstanceId("order-approval-" + orderId),
    new WorkflowDefinitionId("orders", "approval"),
    null, submitterId, Map.of("orderId", orderId)));

HumanTask task = workflowProvider.listTasks(new TaskQuery(
    instance.id(), approverId, null, null, null, HumanTaskStatus.PENDING, null, 50, null))
    .get(0);

workflowProvider.act(new ActionRequest(instance.id(), new ApproveAction(
    UUID.randomUUID().toString(), task.id(), approverId, "ok", Map.of(), Instant.now())));
```

- `start` 按 `instanceId` 幂等；`act` 按 `actionId` 幂等，重复调用返回当前快照。
- 可选能力（加签/减签/转办/委托/撤回/抄送/催办/恢复）必须先查 `capabilities()`；当前内存实现 21 项全为 `true`。
- 超时、`dueAt`、`delegateFor` 全部使用 Provider 时钟；审计对账用 `provider.now()`。
- 会签/或签/驳回/死锁由 Provider 计算，业务方不得自行聚合（口径见契约 §1.5.1）。
- `ACTIVITY` / `COMPENSATION` 在内存实现中立即完成：`core` 没有外部执行器契约，需要真实执行器的场景应使用后续 Temporal/Flowable/Camunda 适配器。
- 工作流 SPI 的冻结基线以 `WorkflowProvider` 接口与 `config/public-api.txt` 为准。

## 4.4 Outbox（事务性可靠投递）

Outbox 是独立能力，不依赖 eventbus；业务方注入 `OutboxWriter`，只表达"把这条记录和业务写入放在同一个事务里"：

```java
@Transactional
public void cancel(OrderId id) {
    orderRepository.save(order);
    outboxWriter.append(OutboxRecord.of(
            idGenerator.id(), "order.cancelled", id.value(), "order.cancelled", payload));
}
```

- `OutboxRecord.messageId` 是幂等键；重复 append 只落一行。
- payload 是不透明字节；outbox 不做序列化。
- relay 异步投递，at-least-once；投递目标由 `OutboxSink` 决定。
- eventbus 的接入方式是实现 `OutboxSink` 的 `EventBusOutboxSink`：`OutboxRecord` → `EventMessage` → `EventBusTransport`。
- 配置：`seedj.outbox.enabled` + `seedj.outbox.provider`；relay 在 `seedj.outbox.relay.*`，存储在 `seedj.outbox.store.*`。

## 5. 新增一个适配器（例如 Vault）

1. 实现 `core` 的入口接口（如 `TransitProvider`）；
2. 在实现模块里加 `@AutoConfiguration` + `@Bean`，并声明 `@ConditionalOnMissingBean`；
3. 若同能力已有多个实现，加 `seedj.<cap>.provider` 选择；
4. `extends` 对应的契约测试类（`core` test-jar），一行即可；
5. 在本表加一行，并开一个 `decision` issue 说明"为什么需要第二个适配器"。

| 能力 | 内置适配器 | 契约测试 |
|---|---|---|
| PKI | `BcPkiProvider`（BC + 状态文件） | `PkiProviderContractTest` |
| Transit | `BcTransitProvider`（JCE + 状态文件） | `TransitProviderContractTest` |
| EventBus 队列（发布） | `FileEventBusTransport` / `MemoryEventBusTransport` | `EventBusTransportContractTest` |
| EventBus 队列（消费组） | `FileEventBusTransport` / `MemoryEventBusTransport` | `EventConsumerStoreContractTest` |
| Outbox 存储 | `PostgresOutboxStore` | `OutboxStoreContractTest` |
| Outbox 投递 | `EventBusOutboxSink`（eventbus 适配器） | `OutboxRelayTest` + `EventBusOutboxSinkTest` |
| Outbox 迁移 | `JdbcMigrationRunner` | `JdbcMigrationRunnerTest` |
| ID | `SnowflakeIdGenerator` / `StringIdGenerator` | `IdGeneratorContractTest` |
| Workflow | `InMemoryWorkflowProvider` | `WorkflowProviderContractTest` |

## 6. 验收命令

```bash
# 契约 + 装配
mvn -pl core,seedj-infra-local,seedj-jdbc-postgresql,seedj-iam-starter,seedj-biz-starter,seedj-server,seedj-it -am test

# 端到端（需要 Docker）
mvn -o -pl starter -am verify

# 形状检查（必须为 0）
grep -rn "ServiceLoader" --include=*.java .
grep -rn "new BcTransitProvider\|new BcPkiProvider" --include=*.java starter/src/main

# outbox 不得依赖 eventbus（必须为 0）
grep -rn "^import cn\.byteforce\.seedj\.eventbus" \
  core/src/main/java/cn/byteforce/seedj/outbox \
  seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox
```
