# Outbox 独立 SPI 设计：outbox 作为一级基础设施能力

- 状态：已实施（2026-11-05）
- 日期：2026-11-05
- 关联：`docs/spi/README.md`、`docs/adr/ADR-0002-infra-spi-constitution.md`
- 随实现新增：`docs/adr/ADR-0004-outbox-standalone-spi.md`

## 1. 意图

Outbox 是"事务性写入 + 可靠投递"的通用机制，不是 EventBus 的私有实现。本次把 outbox 提升为与 pki / transit / eventbus 平级的一级能力：

- 业务方只认识 `OutboxWriter`（写一条记录）和 `OutboxRecord`（记录本身）。
- 存储、claim、lease、heartbeat、retry、dead-letter 是机制，只有 provider 和 relay 看得见。
- EventBus 成为 outbox 的**第一个投递适配器**：它实现 `OutboxSink`，不再拥有 outbox 的存储、relay 和租约。
- 依赖方向单向：`eventbus → outbox`（可选）。`cn.byteforce.seedj.outbox.**` 不得 import `cn.byteforce.seedj.eventbus.**`。

这样做的收益：

1. workflow / IAM / 未来的业务模块可以在自己的事务里写 outbox，不需要 eventbus 在场；
2. EventBus 不安装 outbox 时仍然可以直接投递（本地开发），行为与今天一致；
3. outbox 的 relay、指标、迁移可以脱离 eventbus 单独测试；
4. eventbus 的消费侧不再被 outbox 的命名污染。

## 2. 目标 / 非目标 / 反目标

### 目标

1. `cn.byteforce.seedj.eventbus` 与 `.spi` 中不再出现 `Outbox*` 类型；eventbus 业务包的白名单里不再有 `OutboxMessage`。
2. 新增 `cn.byteforce.seedj.outbox`（业务入口）与 `cn.byteforce.seedj.outbox.spi`（机制契约），`core` 继续零 Spring 依赖。
3. 新增 Maven 模块 `seedj-outbox-starter`：relay、writer、指标、自动配置；只依赖 `core`。
4. `domain-jdbc-postgresql` 只提供 outbox 存储实现，配置键改为 `seedj.outbox.*`。
5. `starter` 保留 eventbus 装配，并通过 `EventBusOutboxSink` + `EventOutboxMapper` 接入 outbox。
6. 契约测试 TCK、装配测试、端到端测试与"包依赖方向"门禁覆盖新边界。
7. 配置键、指标名、错误码随能力迁移。`1.0.0` 未发布，旧键不保留。

### 非目标

1. 多 sink 路由：一个应用一个 `OutboxSink`。同时需要 eventbus 和 webhook 的应用自己提供组合 sink。
2. 推送式 relay（PostgreSQL `LISTEN/NOTIFY`、CDC/Debezium）。仍然轮询。
3. outbox 内做序列化：payload 是不透明字节，序列化属于生产者。
4. 改 eventbus 消费侧语义、消费者组模型、`EventConsumerStore`。
5. 改 relay 的调度、心跳、退避算法（只换依赖的 SPI 类型）。
6. 把 `InfrastructureInitializer` 从 `starter` 抽成共享模块。
7. 拆分 eventbus 自身的 producer/consumer 自动配置；本次只把 relay 移走。
8. 重命名 `starter` 聚合模块或 `seedj-iam-starter`。

### 反目标

1. 业务方为了写 outbox 被迫理解 claim / lease / attempts。
2. outbox 依赖 `DomainEvent` / `EventMessage` / `EventBusTransport`。
3. 为了"未来可能的多 sink"现在引入泛型 `OutboxStore<T>` 或路由表。
4. 保留 `OutboxMessage` 这种"双肩挑"类型，或保留 `seedj.eventbus.outbox.*` 旧配置兼容层。

## 3. 现状与耦合证据

| # | 耦合点 | 位置 |
| --- | --- | --- |
| 1 | transport 依赖以 outbox 命名的消息类型 | `EventBusTransport.send(OutboxMessage)`、`ReceivedEvent.message()` |
| 2 | 消费侧死信复用 outbox 的失败类型 | `EventConsumerStore.retry(..., OutboxFailure)` |
| 3 | 业务包公开 outbox 类型 | `SpiSurfaceTest` 的 `eventbus` 白名单包含 `OutboxMessage`；`EventPublisher.publish(OutboxMessage)` |
| 4 | 错误码混合 | `EventBusErrorCode` 同时有 `TX_REQUIRED`/`LEASE_LOST`/`OUTBOX_UNAVAILABLE` 与 `CONSUMER_FAILED` |
| 5 | 指标混合 | `RelayMetrics` 同时有 relay 与 consumer 计数 |
| 6 | 配置混合 | `EventBusProperties` 一个类装 `relay` + `consumer` |
| 7 | 装配混合 | `starter/.../eventbus/wiring/OutboxRelay` 与 `EventConsumerContainer` 同一包、同一自动配置 |
| 8 | 存储实现带 eventbus 字段 | `PostgresOutboxStore` / `outbox` 表使用 `event_id`/`topic`/`event_type`/`event_version` |

模块层面其实已经分开：`domain-jdbc-postgresql` 只实现 outbox，`domain-infra-memory` 只实现 transport + consumer store，两者互不依赖。真正混在一起的是**契约包、消息类型、装配和配置**。所以本次不需要拆现有 Maven 模块的依赖图，只新增 outbox 自己的装配模块。

历史原因：ADR-0002 先有 outbox、后加 transport，两者共用了已经叫开的 `OutboxMessage`。属于顺序问题，不是设计推导。

## 4. 方案取舍

### A. 不透明记录 + `OutboxSink` + 独立 starter（采用）

outbox 拥有 `OutboxRecord`（payload 字节）、`OutboxStore`（存储）和 `OutboxSink`（出口）；eventbus 用适配器把 `OutboxRecord` 映射回 `EventMessage` 再写 `EventBusTransport`。outbox 完全不认识事件模型。

- 优点：真正的 SPI；任何生产者可复用；存储/relay 可独立测试；eventbus 可缺席。
- 代价：新增一个模块、一层 1:1 映射、配置键迁移。

### B. 只拆契约包，relay 留在 `starter`（否决）

类型层面解耦，改动最小；但要只用 outbox 就必须拉进聚合 `starter`（IAM、security、actuator 全带上），"独立能力"只在纸面上成立。第一个非 eventbus 使用方出现时还要再拆一次。

### C. 泛型 `OutboxStore<T>` / `OutboxRecord<T>`（否决）

省掉映射层，但 JDBC 最终仍要字节和序列化器，泛型只是把序列化 seam 换个位置；泛型样板会摊到每个适配器和 TCK 上，收益不抵成本。

### 模块拓扑

```
core
 ├─ cn.byteforce.seedj.outbox        OutboxWriter, OutboxRecord, OutboxException, OutboxErrorCode
 ├─ cn.byteforce.seedj.outbox.spi    OutboxStore, OutboxSink, ClaimRequest/ClaimToken/ClaimedRecord, OutboxFailure
 ├─ cn.byteforce.seedj.eventbus      EventPublisher, EventSubscriber, EventEnvelope, EventMessage
 └─ cn.byteforce.seedj.eventbus.spi  EventBusTransport, EventConsumerStore, PollRequest, ReceivedEvent,
                                     DeadLetter, DeliveryFailure, EventPayloadSerializer

seedj-outbox-starter（新，依赖 core）
 └─ cn.byteforce.seedj.outbox.wiring  OutboxAutoConfiguration, OutboxRelay, DefaultOutboxWriter,
                                      OutboxInfrastructureInitializer, OutboxProperties, OutboxMetrics

domain-jdbc-postgresql（依赖 core）
 └─ cn.byteforce.seedj.outbox.jdbc    PostgresOutboxStore, PostgresOutboxMigrations,
                                      PostgresOutboxSchemaCapability, PostgresOutboxAutoConfiguration,
                                      PostgresOutboxProperties

domain-infra-memory（依赖 core）
 └─ cn.byteforce.seedj.eventbus.transport  transport + consumer store（不 import outbox）

starter（依赖 core、seedj-outbox-starter、memory、postgres、iam-starter）
 └─ cn.byteforce.seedj.eventbus.wiring  EventBusAutoConfiguration, DefaultEventPublisher,
                                        EventBusOutboxSink, EventOutboxMapper, EventConsumerContainer,
                                        EventTopicCapability, EventBusMetrics
```

`seedj-outbox-starter` 是装配模块，不是实现模块；`domain-jdbc-postgresql` 只依赖 `core`，不依赖 outbox starter，避免实现反向依赖装配。

## 5. 契约语义

### 5.1 `OutboxRecord`（`cn.byteforce.seedj.outbox`）

```java
public record OutboxRecord(
        String messageId,
        String destination,
        String partitionKey,
        String contentType,
        int contentVersion,
        byte[] payload,
        Map<String, String> headers,
        Instant occurredAt) {

    public static OutboxRecord of(String destination, String partitionKey,
                                  String contentType, byte[] payload);
}
```

- `messageId` 是幂等键：相同 `messageId` 重复 append 只落一行。
- `destination` 是投递目标，语义由 `OutboxSink` 解释；eventbus 里就是 topic。
- `contentType` / `contentVersion` 是 payload 的 schema 标识；outbox 不解释它们。
- `payload` 防御性拷贝；`headers` 不可变拷贝；`toString` 不输出 payload。
- 校验：`messageId`/`destination`/`contentType`/`payload`/`occurredAt` 非空，`contentVersion >= 1`。
- `of(...)` 生成随机 UUID `messageId`、`contentVersion=1`、空 headers、`Instant.now()`；需要确定性幂等的生产者用规范构造器。

### 5.2 `OutboxWriter`（业务入口）

```java
public interface OutboxWriter {
    void append(OutboxRecord record);
}
```

契约：

1. 必须在调用方的**活动事务内**调用，与业务写入同生共死；无法检测事务的 provider 必须在文档中声明。
2. 按 `messageId` 幂等；重复 append 不产生第二行。
3. 只做持久化，不同步投递；投递由 relay 异步完成（at-least-once）。
4. 失败抛 `OutboxException`；不得抛裸 `IllegalStateException`。
5. 业务方不接触 `claim` / `markSent` / lease。
6. 没有可用 `OutboxStore` 时抛 `OutboxException(STORE_UNAVAILABLE)`；这是配置错误，不静默丢弃。

### 5.3 `OutboxStore`（机制契约，`outbox.spi`）

```java
public interface OutboxStore extends AutoCloseable {
    void append(OutboxRecord record);
    List<ClaimedRecord> claim(ClaimRequest request);
    void markSent(ClaimToken token);
    void markFailed(ClaimToken token, OutboxFailure failure, Duration retryDelay);
    default boolean supportsRenewal() { return false; }
    default boolean renewLease(ClaimToken token) { return false; }
    void retry(String recordId, String operator);
    void discard(String recordId, String operator);
    default void close() { }
}
```

语义与今天的 `OutboxStore` 一致，只把 `OutboxMessage` 换成 `OutboxRecord`：

- `append` 与业务写入同事务，`messageId` 幂等。
- `claim` 按 `partitionKey` 内顺序排他领取；租约到期可回收。
- `markSent` / `markFailed` 必须校验 `ClaimToken`，租约失效抛 `OutboxException(LEASE_LOST)`。
- `retry` / `discard` 只对 FAILED 记录生效。
- `close()` 幂等且不抛。

`ClaimedRecord` 字段：`String id`（存储行 id）、`OutboxRecord record`、`int attempts`、`ClaimToken token`、`Instant leaseUntil`。

`OutboxFailure` 保留在 outbox（`errorType`、`errorMessage`、`retryable`）。

### 5.4 `OutboxSink`（出口契约，`outbox.spi`）

```java
public interface OutboxSink {
    CompletionStage<Void> deliver(OutboxRecord record);
}
```

契约：

1. at-least-once；同一 `messageId` 可能重复投递，sink 侧按 `messageId` 幂等。
2. 失败返回 exceptionally completed stage 或抛 `OutboxException`，不得静默丢弃。
3. 不修改传入 record。
4. sink 不负责 claim/lease；生命周期由提供方管理，不由 relay `close()`。
5. 一个应用只允许一个 `OutboxSink` bean；多个 bean 时 relay 启动失败并给出明确消息（多 sink 路由是非目标）。

### 5.5 错误模型

新增 `OutboxException extends InfrastructureException` 与 `OutboxErrorCode implements ErrorDescriptor`：

```java
public class OutboxException extends InfrastructureException {
    public OutboxException(OutboxErrorCode code, String message);
    public OutboxException(OutboxErrorCode code, String message, Throwable cause);
    public OutboxErrorCode getErrorCode();
}
```

| 错误码 | 类别 | retryable | 含义 |
| --- | --- | --- | --- |
| `TX_REQUIRED` | CLIENT_ERROR | false | 事务外 append |
| `INVALID_RECORD` | CLIENT_ERROR | false | 记录字段不合法 |
| `LEASE_LOST` | CLIENT_ERROR | false | outbox 记录租约失效 |
| `RECORD_NOT_FOUND` | CLIENT_ERROR | false | 记录不存在或状态不允许运维操作 |
| `STORE_UNAVAILABLE` | CONFIG_ERROR | false | 没有可用的 `OutboxStore`（装配缺失，不是瞬时故障） |
| `DELIVERY_FAILED` | BACKEND_ERROR | true | sink 无法投递（含 eventbus 没有 transport） |
| `PROVIDER_CLOSED` | CONFIG_ERROR | false | provider 已关闭 |
| `INTERNAL_ERROR` | BACKEND_ERROR | false | 其他内部错误 |

`EventBusErrorCode` 保留 eventbus 自己的错误，新增 `TRANSPORT_UNAVAILABLE`（替换文件 transport 里误用的 `OUTBOX_UNAVAILABLE`）：

- 保留：`LEASE_LOST`（消费租约）、`NO_TRANSPORT`、`NO_SERIALIZER`、`SERIALIZATION_FAILED`、`NO_CONSUMER_STORE`、`INVALID_SUBSCRIBER`、`CONSUMER_FAILED`、`SEND_FAILED`、`PROVIDER_CLOSED`、`INTERNAL_ERROR`。
- 新增：`TRANSPORT_UNAVAILABLE`（BACKEND_ERROR, retryable）。
- 移除：`TX_REQUIRED`、`RECORD_NOT_FOUND`、`OUTBOX_UNAVAILABLE`、`INVALID_MESSAGE`（迁到 `OutboxErrorCode`，`INVALID_MESSAGE` 改名为 `INVALID_RECORD`）。

跨能力异常：outbox 激活时 `EventPublisher.publish` 可能抛 `OutboxException(TX_REQUIRED)`。不做重包装；`EventPublisher` Javadoc 明确写出，业务需要能力无关处理时 catch `InfrastructureException`。

### 5.6 与 EventBus 的映射

| `EventMessage`（eventbus） | `OutboxRecord`（outbox） |
| --- | --- |
| `eventId` | `messageId` |
| `topic` | `destination` |
| `partitionKey` | `partitionKey` |
| `eventType` | `contentType` |
| `eventVersion` | `contentVersion` |
| `payload` | `payload` |
| `headers` | `headers` |
| `occurredAt` | `occurredAt` |

映射代码只存在于 `starter` 的包私有 `EventOutboxMapper`：

```java
static OutboxRecord toRecord(EventMessage message);
static EventMessage toMessage(OutboxRecord record);
```

`EventBusOutboxSink`：

```java
public final class EventBusOutboxSink implements OutboxSink {
    public EventBusOutboxSink(ObjectProvider<EventBusTransport> transports);
    @Override public CompletionStage<Void> deliver(OutboxRecord record);
}
```

- `deliver` 把 `OutboxRecord` 映射成 `EventMessage`，调用 `transport.send(...)`，原样返回 stage。
- 没有 `EventBusTransport` 时返回 failed future：`OutboxException(DELIVERY_FAILED, "no EventBusTransport ...")`，保证 relay 按可重试处理，不丢消息。
- 日志/错误信息使用 `messageId`。

`EventPublisher` 实现（`DefaultEventPublisher`）：

```java
publish(DomainEvent event):
    serializer = ...
    publish(EventMessage.from(event, serializer.serialize(event)));

publish(EventMessage message):
    OutboxStore store = outboxStores.getIfAvailable();
    OutboxWriter writer = outboxWriters.getIfAvailable();
    if (store != null && writer != null) {
        writer.append(EventOutboxMapper.toRecord(message));
        return;
    }
    transport = ...
    transport.send(message) ... // at-most-once，与今天一致
```

`DefaultEventPublisher` 里对 `OutboxStore` 的探测是**装配层**行为（宪法允许装配看见机制）；业务 API `EventPublisher` 不暴露任何 outbox 类型。用探测而不是 `@ConditionalOnBean`，是为了不引入自动配置排序依赖，并保留"没有 outbox 就直发"的降级语义。探测和写入分成两个依赖：`OutboxStore` 只回答"outbox 是否可用"，真正写入走 `OutboxWriter`，保证 outbox 的业务入口只有一个。只有 store 和 writer **同时存在**才走 outbox；`seedj.outbox.enabled=true` 但没有 store（本地开发无数据库）或 outbox 被禁用但用户自己注册了 store 时，都退回直发，不抛 `NoSuchBeanDefinitionException`。

## 6. 实现侧变更

### 6.1 `core`

新增：

```
core/src/main/java/cn/byteforce/seedj/outbox/
    OutboxErrorCode.java
    OutboxException.java
    OutboxRecord.java
    OutboxWriter.java
    package-info.java
core/src/main/java/cn/byteforce/seedj/outbox/spi/
    ClaimRequest.java          （从 eventbus.spi 移入，内容不变）
    ClaimToken.java            （同上）
    ClaimedRecord.java         （message → record）
    OutboxFailure.java         （同上）
    OutboxSink.java            （新增）
    OutboxStore.java           （OutboxMessage → OutboxRecord）
    package-info.java          （改写：outbox 机制契约）
```

`core/src/test/java/cn/byteforce/seedj/outbox/`：

```
OutboxRecordTest.java
OutboxPackageIndependenceTest.java
spi/OutboxStoreContractTest.java   （从 eventbus.spi 移入，异常/类型切换）
```

`eventbus` 侧：

- `OutboxMessage` 重命名为 `EventMessage`，包与字段不变；删除 Javadoc 里"由 OutboxStore 分配 sequence"的段落（sequence 不再属于消息）。
- `eventbus.spi` 删除 `OutboxStore`、`ClaimRequest`、`ClaimToken`、`ClaimedRecord`、`OutboxFailure`；新增 `DeliveryFailure`（同三字段记录）。
- `EventBusErrorCode` 按 §5.5 调整。
- `EventBusException` Javadoc 改为只描述 eventbus。
- `EventPublisher`：`publish(OutboxMessage)` → `publish(EventMessage)`；Javadoc 说明 outbox 激活时抛 `OutboxException`。
- `EventConsumerStore` / `DeadLetter` / `ReceivedEvent`：类型改为 `EventMessage`、`DeliveryFailure`。
- `package-info.java` 更新。

`core` 不新增任何 Spring 依赖。

### 6.2 `seedj-outbox-starter`（新模块）

模块 POM：

- `parent` = `root`；`artifactId` = `seedj-outbox-starter`。
- 依赖：`core`、`spring-boot-autoconfigure`、`slf4j-api`；`micrometer-core` 为 optional；测试用 `spring-boot-starter-test`（与 `seedj-iam-starter` 的写法保持一致）。
- `dependencyManagement` import `bom`（与 `core`/`starter` 一致）。
- 在根 `pom.xml` 的 `<modules>` 和 `bom/pom.xml` 的 `dependencyManagement` 中登记；`starter` 增加对它的依赖。

类：

- `OutboxProperties`：`@ConfigurationProperties(prefix = "seedj.outbox")`，字段 `boolean enabled = true`、`Relay relay`。`Relay` 字段与今天的 `EventBusProperties.Relay` 完全一致（`enabled`、`pollInterval`、`batchSize`、`lease`、`maxConcurrency`、`retryBase`、`retryMax`、`leaseRenewFailures`）。
- `DefaultOutboxWriter implements OutboxWriter`：持有 `ObjectProvider<OutboxStore>`；`append` 时解析 store，没有则抛 `OutboxException(STORE_UNAVAILABLE)`。
- `OutboxRelay implements InitializingBean, AutoCloseable`：从 `starter` 的 `OutboxRelay` 迁移；依赖改为 `ObjectProvider<OutboxStore>` + `ObjectProvider<OutboxSink>`；`busTransport.send(record.message())` 改为 `sink.deliver(record.record())`；`EventBusException` 改为 `OutboxException`；日志前缀 `seedj outbox relay`；算法（claim / heartbeat / markSent / markFailed / backoff / close）不变。
- `OutboxInfrastructureInitializer implements InitializingBean`：`IamInfrastructureInitializer` 的同构副本，bean 名 `outboxInfrastructureInitializer`。用于"只依赖 `seedj-outbox-starter` + postgres 实现"的应用仍能执行迁移。与聚合 `starter` 的 `InfrastructureInitializer` 同时存在时，靠 `isReady()` 幂等（与 IAM 现状一致）。
- `OutboxMetrics` + `MicrometerOutboxMetrics`：`claimed(int)`、`sent()`、`failed(boolean)`、`leaseLost()`、`claimFailed()`；`noop()` 静态方法。
- `OutboxAutoConfiguration`：
  - `@AutoConfiguration` + `@ConditionalOnProperty(prefix = "seedj.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)` + `@EnableConfigurationProperties(OutboxProperties.class)`。
  - `@Bean @ConditionalOnMissingBean OutboxWriter outboxWriter(ObjectProvider<OutboxStore>)`。
  - `@Bean OutboxInfrastructureInitializer outboxInfrastructureInitializer(ObjectProvider<InfrastructureCapability>)`。
  - `@Bean(destroyMethod = "close") @ConditionalOnMissingBean @DependsOn("outboxInfrastructureInitializer") OutboxRelay outboxRelay(ObjectProvider<OutboxStore>, ObjectProvider<OutboxSink>, OutboxProperties, OutboxMetrics)`。
  - 嵌套的 micrometer 条件配置，复制现有 `NoMetricsConfiguration`/`MicrometerMetricsConfiguration` 的写法。
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：`cn.byteforce.seedj.outbox.wiring.OutboxAutoConfiguration`。

`OutboxRelay` 的空转规则：没有 `OutboxStore` → info "no OutboxStore on the classpath"；没有 `OutboxSink` → info "no OutboxSink on the classpath"；都不阻断启动。没有 sink 时已写入的记录保留在 outbox 表中（PENDING），等 sink 接入后继续投递——不删除、不死信。

### 6.3 `domain-jdbc-postgresql`

包整体迁移：`cn.byteforce.seedj.jdbc` → `cn.byteforce.seedj.outbox.jdbc`，涉及：

- `PostgresOutboxStore`（imports 改为 outbox 类型；`EventBusException` → `OutboxException`；`EventBusErrorCode` → `OutboxErrorCode`；SQL 列名见下）。
- `PostgresOutboxMigrations`（新增 V2）。
- `PostgresOutboxSchemaCapability`（`NAME = "postgres-outbox"`，`MIGRATION_SCOPE = "outbox"`）。
- `OutboxAutoConfiguration` 重命名为 `PostgresOutboxAutoConfiguration`，条件键改为 `seedj.outbox.enabled` / `seedj.outbox.provider`。
- `OutboxProperties` 重命名为 `PostgresOutboxProperties`，前缀 `seedj.outbox.store`，只留 `appendMaxAttempts = 3`。
- `META-INF/spring/...AutoConfiguration.imports` 更新类名。

`JdbcMigrationRunner` 留在 `cn.byteforce.seedj.jdbc`（IAM 也在用），不迁移。

迁移：

- V1 保持内容不变（checksum 不变）。
- 新增 V2：

```sql
ALTER TABLE outbox RENAME COLUMN event_id TO message_id;
ALTER TABLE outbox RENAME COLUMN topic TO destination;
ALTER TABLE outbox RENAME COLUMN event_type TO content_type;
ALTER TABLE outbox RENAME COLUMN event_version TO content_version;
```

- `scope` 从 `eventbus-outbox` 改为 `outbox`。已有开发库在旧 scope 下的历史行继续保留但不再被校验；新 scope 的 V1 在已有表上是 `CREATE TABLE IF NOT EXISTS` 空操作，随后 V2 完成列重命名。全新库按 V1 → V2 执行。
- 表名 `outbox` / `outbox_audit` 不变；`ClaimToken.recordId` 仍是行主键 `id`。

`PostgresOutboxStore` 的 SQL 同步修改：`event_id` → `message_id`、`topic` → `destination`、`event_type` → `content_type`、`event_version` → `content_version`；`ON CONFLICT (message_id)`。`OutboxRowMapper` 构造 `OutboxRecord(message_id, destination, partition_key, content_type, content_version, ...)`。

### 6.4 `starter`

- `DefaultEventPublisher`：按 §5.6 改造；构造参数从 `ObjectProvider<OutboxStore> outboxStore` 改为同时持有 `ObjectProvider<OutboxStore>` 与 `ObjectProvider<OutboxWriter>`（后者用于 append）。`EventBusAutoConfiguration.eventPublisher(...)` 同步增加 `ObjectProvider<OutboxWriter>` 参数。
- 新增 `EventOutboxMapper`（包私有）与 `EventBusOutboxSink`。
- `EventBusAutoConfiguration`：
  - 删除 `outboxRelay` bean 与对 `OutboxStore`/`OutboxRelay` 的 import；删除 `@DependsOn("infrastructureInitializer")` 中只服务于 relay 的部分。
  - 新增 `@Bean @ConditionalOnMissingBean(OutboxSink.class) OutboxSink eventBusOutboxSink(ObjectProvider<EventBusTransport>)`。
  - 保留 `EventPublisher`、`EventConsumerContainer`、`EventTopicCapability`、`InfrastructureCapability`、序列化器与 metrics 装配。
  - 类 Javadoc 去掉 outbox/relay 描述。
- `EventBusProperties`：删除内部 `Relay` 类与 `getRelay()`；保留 `enabled`、`topics`、`consumer`。
- `EventConsumerContainer`：`OutboxFailure` → `DeliveryFailure`。
- `RelayMetrics` → `EventBusMetrics`：只保留 `consumed()`、`consumerFailed(boolean)`、`deadLettered()`、`pollFailed()`；`MicrometerRelayMetrics` → `MicrometerEventBusMetrics`；指标名保留 `seedj.eventbus.consumer.*`。
- `OutboxRelay`/`Heartbeat` 从 `starter` 删除（迁入 outbox starter）。
- `EventTopicCapability` 不变。

### 6.5 `domain-infra-memory`

只做类型重命名，不引入 outbox 依赖：

- `OutboxMessage` → `EventMessage`。
- `OutboxFailure` → `DeliveryFailure`。
- `EventBusErrorCode.OUTBOX_UNAVAILABLE` → `EventBusErrorCode.TRANSPORT_UNAVAILABLE`（文件 transport 的 I/O 失败）。
- `EventBusErrorCode.LEASE_LOST` 保留（消费租约）。

### 6.6 测试

- `core`：`OutboxRecordTest`、`OutboxStoreContractTest`（新包）、`OutboxPackageIndependenceTest`（从模块 basedir 扫描 `src/main/java/cn/byteforce/seedj/outbox` 下的 `*.java`，断言没有以 `import cn.byteforce.seedj.eventbus` 开头的行；只看 import，避免 Javadoc 里的说明文字误报）。
- `core` 既有测试跟随重命名：`OutboxMessageTest` → `EventMessageTest`；`EventBusTransportContractTest`（`OutboxMessage` → `EventMessage`）、`EventConsumerStoreContractTest`（`OutboxFailure` → `DeliveryFailure`）。
- `domain-infra-memory` 的 transport/consumer 测试跟随 `EventMessage`/`DeliveryFailure` 重命名（`FileEventBusTransportContractTest`、`FileEventConsumerGroupTest`、`MemoryEventBusTransportContractTest`、`MemoryEventConsumerStoreContractTest`、`FileEventConsumerStoreContractTest`）。
- `seedj-outbox-starter`：`OutboxRelayTest`（用 fake store/sink 驱动 `relay()`：成功 markSent、可重试失败 markFailed、不可重试死信、租约丢失跳过 markSent、无 sink 空转、close 幂等）、`OutboxAutoConfigurationTest`（有/无 store、`seedj.outbox.enabled=false`、writer 懒失败）、`OutboxStarterPackageIndependenceTest`（扫描本模块 `src/main` 的 import 行，规则同上）。
- `domain-jdbc-postgresql`：`PostgresOutboxStoreContractTest`（新包、`OutboxRecord`）、`PostgresOutboxAutoConfigurationTest`（新配置键、V2 列存在、history 2 行、`TX_REQUIRED` 抛 `OutboxException`）、`PostgresOutboxPackageIndependenceTest`（扫描本模块 `src/main/java/cn/byteforce/seedj/outbox` 的 import 行）。
- `starter`：`EventBusOutboxSinkTest`（映射与失败 stage）、`DefaultEventPublisherTest`（有 outbox append / 无 outbox 直发 / 只有 store 没有 writer 时直发 / 无 serializer / 无 transport）、`EventConsumerContainer` 相关测试改用 `DeliveryFailure`、`EventBusIT` 与 `ImTestController` 改用 `EventMessage` + `message_id` 列（`ImTestController` 的 `OutboxStore` import 改为 `outbox.spi`）。
- `SpiSurfaceTest`：新增 `cn.byteforce.seedj.outbox`（`OutboxErrorCode`、`OutboxException`、`OutboxRecord`、`OutboxWriter`）与 `cn.byteforce.seedj.outbox.spi`（`ClaimRequest`、`ClaimToken`、`ClaimedRecord`、`OutboxFailure`、`OutboxSink`、`OutboxStore`）；eventbus 两个白名单按新类型更新。
- `ClusterExtension`：`seedj.eventbus.relay.*` → `seedj.outbox.relay.*`；`seedj.eventbus.outbox.enabled` → `seedj.outbox.enabled`。
- `seedj-iam-starter` 测试：`seedj.eventbus.outbox.enabled=false` → `seedj.outbox.enabled=false`（2 处）。

## 7. 装配与配置

### 7.1 自动配置

| 模块 | 自动配置 | 条件 |
| --- | --- | --- |
| `seedj-outbox-starter` | `OutboxAutoConfiguration` | `seedj.outbox.enabled`（默认 true） |
| `domain-jdbc-postgresql` | `PostgresOutboxAutoConfiguration` | `seedj.outbox.enabled` + `seedj.outbox.provider=postgres` + `DataSource`/`JdbcTemplate` + PG driver |
| `starter` | `EventBusAutoConfiguration` | `seedj.eventbus.enabled`（默认 true） |

`seedj.outbox.enabled=false` 的效果：没有 `OutboxWriter`、`OutboxRelay`、`OutboxStore`；`EventPublisher` 退回直接写 transport（at-most-once），与今天"没有 outbox"一致。

### 7.2 配置键迁移

| 旧键 | 新键 | 归属 |
| --- | --- | --- |
| `seedj.eventbus.outbox.enabled` | `seedj.outbox.enabled` | outbox starter / postgres |
| `seedj.eventbus.outbox.provider` | `seedj.outbox.provider` | postgres 自动配置 |
| `seedj.eventbus.outbox.append-max-attempts` | `seedj.outbox.store.append-max-attempts` | postgres |
| `seedj.eventbus.relay.*` | `seedj.outbox.relay.*` | outbox starter |
| `seedj.eventbus.consumer.*` | 不变 | eventbus |
| `seedj.eventbus.transport` / `file.*` / `memory.*` / `topics` | 不变 | eventbus / memory |

不保留旧键别名。`1.0.0` 未发布，迁移成本由仓库内的 `ClusterExtension`、IAM 测试与文档一次性吸收。

### 7.3 指标

| 旧指标 | 新指标 |
| --- | --- |
| `seedj.eventbus.outbox.claimed` | `seedj.outbox.claimed` |
| `seedj.eventbus.outbox.sent` | `seedj.outbox.sent` |
| `seedj.eventbus.outbox.failed` | `seedj.outbox.failed` |
| `seedj.eventbus.outbox.lease.lost` | `seedj.outbox.lease.lost` |
| `seedj.eventbus.outbox.claim.failed` | `seedj.outbox.claim.failed` |
| `seedj.eventbus.consumer.*` | 不变 |

### 7.4 启动顺序

- `OutboxRelay` 通过 `@DependsOn("outboxInfrastructureInitializer")` 排在 outbox 迁移之后。
- `outboxInfrastructureInitializer` 与聚合 `starter` 的 `InfrastructureInitializer` 都会执行全部 `InfrastructureCapability`；`isReady()` 保证幂等。这是 IAM starter 已有的模式，本次沿用。
- relay 解析 `ObjectProvider<OutboxSink>` 发生在 `afterPropertiesSet`；sink 内部对 `EventBusTransport` 的解析延迟到 `deliver` 调用，避免 bean 创建顺序问题。没有 transport 时按可重试失败处理。

## 8. 兼容性与迁移

`1.0.0` 未发布（`revision=1.0.0-SNAPSHOT`），无已发布 API 基线，不升 MAJOR。

### 8.1 Java 类型

| 旧 | 新 |
| --- | --- |
| `cn.byteforce.seedj.eventbus.OutboxMessage` | `cn.byteforce.seedj.eventbus.EventMessage` |
| `EventPublisher.publish(OutboxMessage)` | `EventPublisher.publish(EventMessage)` |
| `eventbus.spi.OutboxStore` / `ClaimRequest` / `ClaimToken` / `ClaimedRecord` / `OutboxFailure` | `outbox.spi.*` |
| `OutboxStore.append(OutboxMessage)` | `OutboxStore.append(OutboxRecord)` |
| `ClaimedRecord.message()` | `ClaimedRecord.record()` |
| `EventConsumerStore.retry(..., OutboxFailure)` | `EventConsumerStore.retry(..., DeliveryFailure)` |
| outbox 相关 `EventBusException` / `EventBusErrorCode` | `OutboxException` / `OutboxErrorCode` |
| `starter...RelayMetrics` | `starter...EventBusMetrics` + `outbox...OutboxMetrics` |

`OutboxFailure` 与 `DeliveryFailure` 故意保留为两个类型：outbox 失败描述的是"投递到 sink 失败"，eventbus 失败描述的是"消费处理失败"；共用会再次制造跨能力耦合。

### 8.2 数据库

- 表名不变；列名由 V2 迁移重命名（见 §6.3）。
- 旧 scope `eventbus-outbox` 的历史行保留，不删除；新 scope `outbox` 从 V1 开始。
- 已有开发库无需手工干预；生产未发布，无兼容承诺。

### 8.3 配置/指标

见 §7.2 / §7.3。所有仓库内引用（约 25 处）随实现一次性修改。

### 8.4 文档

- 更新 `docs/spi/README.md`：能力清单、模块表、配置速查、适配器表、验收命令。
- 新增 `docs/adr/ADR-0004-outbox-standalone-spi.md`：决策、后果、替代方案、迁移。
- `docs/adr/ADR-0002-infra-spi-constitution.md` 加一行"outbox 位置由 ADR-0004 调整"，不改写历史。

## 9. 验证

```bash
mvn -o -pl core,domain-infra-memory,domain-jdbc-postgresql,seedj-outbox-starter,starter -am test
mvn -o -pl starter -am verify          # 含 EventBusIT/EventConsumeIT，需要 Docker

# 形状门禁：outbox 不得依赖 eventbus
grep -rn "^import cn\.byteforce\.seedj\.eventbus" \
  core/src/main/java/cn/byteforce/seedj/outbox \
  seedj-outbox-starter/src/main \
  domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox
# 期望：无输出

# 形状门禁：transport/consumer 不得依赖 outbox
grep -rn "^import cn\.byteforce\.seedj\.outbox" \
  domain-infra-memory/src/main/java/cn/byteforce/seedj/eventbus
# 期望：无输出
```

测试矩阵：

| 变更 | 测试 |
| --- | --- |
| `OutboxRecord` 校验/拷贝/脱敏 | `OutboxRecordTest` |
| 存储语义（幂等、分区顺序、租约、死信、运维） | `OutboxStoreContractTest` + `PostgresOutboxStoreContractTest` |
| relay 语义 | `OutboxRelayTest` |
| 装配（有/无 store、禁用、无 sink） | `OutboxAutoConfigurationTest`、`PostgresOutboxAutoConfigurationTest` |
| eventbus 映射与降级 | `EventBusOutboxSinkTest`、`DefaultEventPublisherTest` |
| 端到端 | `EventBusIT`、`EventConsumeIT` |
| 公开类型白名单 | `SpiSurfaceTest` |
| 依赖方向 | `OutboxPackageIndependenceTest`、`OutboxStarterPackageIndependenceTest`、`PostgresOutboxPackageIndependenceTest` + §9 grep |

## 10. 我的决定（供核对）

1. **`OutboxSink` 作为出口名**，一个应用一个 sink；多 sink 路由明确列为非目标。这样 `OutboxRecord` 的 `destination` 语义由唯一 sink 解释，避免现在引入 `supports(destination)` 和排序规则。
2. **`DefaultEventPublisher` 探测 `OutboxStore` + `OutboxWriter` 两个 bean** 来决定走 outbox 还是直发：两者都在才走 outbox，否则直发。装配层允许看见机制；业务 API 不暴露 outbox 类型。用探测而不是 `@ConditionalOnBean`，避免自动配置排序耦合，也保住本地开发（无 PostgreSQL）时的直发降级。
3. **数据库列通过 V2 迁移重命名**（`event_id`→`message_id` 等），scope 改为 `outbox`。`1.0.0` 未发布，schema 说真话比保留 `event_*` 列更重要。
4. **跨能力异常不重包装**：outbox 激活时 `EventPublisher.publish` 抛 `OutboxException(TX_REQUIRED)`，Javadoc 写明。业务要能力无关处理时 catch `InfrastructureException`。
5. **`OutboxFailure` 与 `DeliveryFailure` 分开**，不放进 `core` 共享包；三字段记录的重复比跨能力耦合便宜。
6. **新模块 `seedj-outbox-starter`**，镜像 `seedj-iam-starter` 的装配与 initializer 模式；接受与聚合 `starter` 的 initializer 重复执行（幂等）。
7. **`EventMessage` 取代 `OutboxMessage`**，`EventPublisher.publish(EventMessage)` 保留为逃生舱。
8. **eventbus 自动配置只移除 relay**；producer/consumer 的自动配置拆分留到后续，不扩大本次 diff。
9. **eventbus 的 `LEASE_LOST` 保留**（消费租约与 outbox 租约同名不同义，各自枚举）；文件 transport 的 `OUTBOX_UNAVAILABLE` 改为 `TRANSPORT_UNAVAILABLE`。

## 11. 留给 builder

- 具体 Javadoc 措辞、日志文案、测试 fixture 的命名。
- `OutboxRelayTest` 如何注入可控时钟/调度（可以像现有 relay 一样直接调用 `relay()` 并阻塞等待 `markSent`；不要求引入新测试库）。
- Maven 依赖的 scope 细节（`slf4j-api`、`micrometer-core` optional）按 `seedj-iam-starter` / `starter` 的现有写法处理。
- 是否为 outbox starter 提供一个测试用的 `InMemoryOutboxStore`（仅 test scope，不作为生产 provider）。
- `PostgresOutboxStoreContractTest` 的类名是否保留（建议保留，只换包）。

## 12. 实施结果

- `core`：75 个单测通过（含 `OutboxRecordTest`、`OutboxStoreContractTest` 的重命名、`SpiSurfaceTest` 两个新包、`OutboxPackageIndependenceTest`）。
- `domain-jdbc-postgresql`：32 个测试通过（含 V2 列重命名、`seedj.outbox.*` 装配、`PostgresOutboxPackageIndependenceTest`）。
- `domain-infra-memory`：109 个测试通过。
- `seedj-outbox-starter`：11 个测试通过（`OutboxRelayTest`、`OutboxAutoConfigurationTest`、包独立性门禁）。
- `starter`：9 个单测 + 110 个端到端用例通过（含 `EventBusIT` / `EventConsumeIT`）。
- 形状 grep：outbox 包对 eventbus 的 import 为 0，transport/consumer 对 outbox 的 import 为 0。
