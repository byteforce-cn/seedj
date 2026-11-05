# EventBus 消费侧与基础设施迁移 Implementation Plan

> 状态：已完成（2026-11-04）。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 EventBus 补齐带消费者组的 subscribe/消费契约，并把 eventbus 的 Spring 装配收回 starter，用 InfrastructureInitializer + 版本化 migration 保证表与 topic 一定存在。

**Architecture:** `core` 定义消费契约与迁移原语；`domain-infra-memory` 的 file/memory transport 同时实现发送与带 group 的消费；`domain-jdbc-postgresql` 用 `JdbcMigrationRunner`（version + checksum + history + advisory lock）替代无版本 SQL；`starter` 持有全部 Spring 装配（EventPublisher、relay、consumer container、topic capability、initializer），删除 `domain-eventbus-spring`。消费组语义：同 group 内多实例竞争，不同 group 各收一份；成功 ack，异常按 retryable 重试或进 DLQ。

**Tech Stack:** JDK 21、Spring Boot 4.1、JdbcTemplate、Testcontainers PostgreSQL 17、JUnit 5、AssertJ。

**Spec:** 本次对话结论 + `docs/assessment/2026-11-04-infra-spi-consistency.md` §6.2 / §9。

## Global Constraints

- `core` 不引入 Spring；所有 Spring 装配只放 `starter/src/main/java/cn/byteforce/seedj`。
- 框架不引入 Flyway；迁移必须 version + checksum + history，可重复执行、并发安全。
- 必需资源（outbox 表、订阅 topic）初始化失败必须阻断启动；可选能力失败只 WARN。
- 消费组：同一 group 内一个事件只被一个实例处理；不同 group 各收到一份。
- 不静默丢消息：消费失败必须 ack / retry / dead-letter 三选一。
- 删除 `domain-eventbus-spring` 模块及其 POM 依赖。

## Review Focus

- 同一 JVM 内两个 file transport 实例共享目录时，claim 必须互斥（`CREATE_NEW` 原子性）。
- 两个应用实例同时启动时的 migration 竞争（PostgreSQL advisory lock）。
- initializer 必须先于 relay / consumer container 执行。
- group 未显式配置时默认取 `spring.application.name`，避免同服务多实例被当成不同 group。
- 反序列化失败/不可重试异常必须直接进 DLQ，不能无限重试。

---

### Task 1: core 基础设施生命周期与迁移原语

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/InfrastructureCapability.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/InfrastructureErrorCode.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/Migration.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/MigrationChecksums.java`
- Create: `core/src/test/java/cn/byteforce/seedj/core/MigrationChecksumsTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java`

**Interfaces:**
- Produces: `InfrastructureCapability` (`getName`, `isReady`, `initialize`, `validate`, `isRequired`, `getOrder`)；`Migration(int version, String description, String content)`；`MigrationChecksums.checksum(String)`；`InfrastructureErrorCode`。

- [x] 写 `MigrationChecksumsTest`：CRLF/行尾空白规范化后 checksum 相同；不同内容 checksum 不同。
- [x] 运行 `mvn -o -pl core test -Dtest=MigrationChecksumsTest`，确认失败。
- [x] 实现四个类型，`validate()` 默认调用 `isReady()`，未就绪抛 `InfrastructureException(NOT_READY)`。
- [x] 更新 `SpiSurfaceTest` 白名单。
- [x] 运行 `mvn -o -pl core test`。

### Task 2: PostgreSQL 版本化迁移 + outbox capability

**Files:**
- Create: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/JdbcMigrationRunner.java`
- Create: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/PostgresOutboxMigrations.java`
- Create: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/PostgresOutboxSchemaCapability.java`
- Modify: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/OutboxAutoConfiguration.java`
- Modify: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/OutboxProperties.java`
- Delete: `domain-jdbc-postgresql/src/main/java/cn/byteforce/seedj/jdbc/PostgresOutboxSchema.java`
- Delete: `domain-jdbc-postgresql/src/main/resources/db/eventbus/postgresql/outbox.sql`
- Create: `domain-jdbc-postgresql/src/test/java/cn/byteforce/seedj/jdbc/JdbcMigrationRunnerTest.java`
- Modify: `domain-jdbc-postgresql/src/test/java/cn/byteforce/seedj/jdbc/PostgresOutboxStoreContractTest.java`
- Modify: `domain-jdbc-postgresql/src/test/java/cn/byteforce/seedj/jdbc/OutboxAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `Migration`、`MigrationChecksums`、`InfrastructureCapability`。
- Produces: `JdbcMigrationRunner(DataSource)`，`migrate(List<Migration>)`、`validate(List<Migration>)`、`isUpToDate(List<Migration>)`；`PostgresOutboxSchemaCapability(JdbcMigrationRunner)`；`OutboxAutoConfiguration` 提供 `InfrastructureCapability` bean。

- [x] 写 `JdbcMigrationRunnerTest`：首次建表+history；重复执行幂等；checksum 不一致失败；两个 runner 并发只执行一次；历史版本本地缺失失败。
- [x] 运行该测试确认失败。
- [x] 实现 runner：事务内 `pg_advisory_xact_lock`、`CREATE TABLE IF NOT EXISTS seedj_schema_history`、逐条执行 `ScriptUtils`、checksum 校验。
- [x] 用 `V1` migration 写 outbox + outbox_audit，删除 `.sql` 与 `PostgresOutboxSchema`。
- [x] `OutboxAutoConfiguration` 注册 runner 与 capability；删除 `schema-initialize` 配置。
- [x] 更新 outbox 测试：显式调用 capability.initialize()；删除“关闭建表”用例。
- [x] 运行 `mvn -o -pl domain-jdbc-postgresql test`。

### Task 3: core 消费契约

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/EventSubscriber.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/EventSubscription.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/EventEnvelope.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/EventConsumerStore.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/PollRequest.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/ReceivedEvent.java`
- Create: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/DeadLetter.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/EventBusTransport.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/eventbus/spi/EventPayloadSerializer.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/eventbus/EventBusErrorCode.java`
- Create: `core/src/test/java/cn/byteforce/seedj/eventbus/EventSubscriptionTest.java`
- Create: `core/src/test/java/cn/byteforce/seedj/eventbus/spi/EventConsumerStoreContractTest.java`

**Interfaces:**
- Produces: `EventSubscriber<E>`（`subscription()`、`onEvent(EventEnvelope<E>)`）；`EventSubscription(group, topics, concurrency)`；`PollRequest(group, topics, limit, lease)`；`ReceivedEvent(deliveryId, group, message, attempts, leaseUntil)`；`EventConsumerStore.poll/ack/retry/deadLetter/deadLetters`；`EventBusTransport.ensureTopic/topicExists`；`EventPayloadSerializer.deserialize`。

- [x] 写 `EventSubscriptionTest` 与抽象 `EventConsumerStoreContractTest`（ack 防重投、不同 group 各一份、租约过期重投、retry 延迟、dead letter）。
- [x] 运行 core 测试确认失败。
- [x] 实现契约类型；`EventConsumerStore` 默认 `close()` 幂等。
- [x] 更新 `SpiSurfaceTest` 白名单。
- [x] 运行 `mvn -o -pl core test`。

### Task 4: file / memory 消费组实现

**Files:**
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/eventbus/transport/FileEventBusTransport.java`
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/eventbus/transport/MemoryEventBusTransport.java`
- Modify: `domain-infra-memory/src/main/java/cn/byteforce/seedj/eventbus/transport/EventTransportAutoConfiguration.java`
- Create: `domain-infra-memory/src/test/java/cn/byteforce/seedj/eventbus/transport/FileEventConsumerStoreContractTest.java`
- Create: `domain-infra-memory/src/test/java/cn/byteforce/seedj/eventbus/transport/MemoryEventConsumerStoreContractTest.java`
- Create: `domain-infra-memory/src/test/java/cn/byteforce/seedj/eventbus/transport/FileEventConsumerGroupTest.java`

**Interfaces:**
- Consumes: Task 3 的 `EventConsumerStore`、`PollRequest`、`ReceivedEvent`。
- Produces: 两个 transport 同时实现 `EventBusTransport` 与 `EventConsumerStore`；file claim 文件格式 `<base>.<deliveryId>.<leaseUntilMillis>.<attempts>`；group 目录 `.groups/<group>/{acks,claims,dlq}`。

- [x] 写 file/memory 契约测试与“两个 file 实例同 group 不重复消费”测试。
- [x] 运行确认失败。
- [x] 实现 file 原子 claim/ack/retry/dlq；memory 用相同状态机。
- [x] 自动配置返回具体类型，确保 Spring 能看到 `EventConsumerStore`。
- [x] 运行 `mvn -o -pl domain-infra-memory test`。

### Task 5: 删除 domain-eventbus-spring，装配收回 starter

**Files:**
- Delete: `domain-eventbus-spring/`
- Modify: `pom.xml`（移除 module）
- Modify: `starter/pom.xml`（移除依赖）
- Create: `starter/src/main/java/cn/byteforce/seedj/core/InfrastructureAutoConfiguration.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/core/InfrastructureInitializer.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/EventBusAutoConfiguration.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/EventBusProperties.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/DefaultEventPublisher.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/OutboxRelay.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/Heartbeat.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/RelayMetrics.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/MicrometerRelayMetrics.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/EventConsumerContainer.java`
- Create: `starter/src/main/java/cn/byteforce/seedj/eventbus/wiring/EventTopicCapability.java`
- Create: `starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Move: `domain-eventbus-spring/src/test/java/...` → `starter/src/test/java/...`
- Create: `starter/src/test/java/cn/byteforce/seedj/core/InfrastructureInitializerTest.java`
- Create: `starter/src/test/java/cn/byteforce/seedj/eventbus/wiring/EventConsumerContainerTest.java`

**Interfaces:**
- Consumes: Task 3/4 的消费契约与 store。
- Produces: `EventBusAutoConfiguration` 提供 `EventPublisher`、`OutboxRelay`、`EventConsumerContainer`、`EventTopicCapability`、Jackson serializer；`InfrastructureInitializer` 在 refresh 前按 order 执行 capability；relay/container 通过 `@DependsOn("infrastructureInitializer")` 排在迁移之后。

- [x] 迁移文件、改 package、删模块与 POM 引用。
- [x] 写 initializer 测试（required 失败阻断、optional 失败继续、order）。
- [x] 写 consumer container 测试（成功 ack、retryable→retry、不可重试→DLQ、默认 group）。
- [x] 实现 container：轮询、泛型解析、反序列化、ack/retry/DLQ、优雅关闭。
- [x] 实现 topic capability：配置 topics + 订阅 topics，启动 ensure。
- [x] 运行 `mvn -o -pl starter -am test -DskipITs`。

### Task 6: 跨服务消费 IT 与文档

**Files:**
- Create: `starter/src/test/java/cn/byteforce/seedj/hr/HrEventSubscriber.java`
- Create: `starter/src/test/java/cn/byteforce/seedj/hr/HrOrderPlaced.java`
- Create: `starter/src/test/java/cn/byteforce/seedj/hr/HrTestController.java`
- Create: `starter/src/test/java/cn/byteforce/seedj/test/EventConsumeIT.java`
- Modify: `starter/src/test/java/cn/byteforce/seedj/test/EventBusIT.java`
- Modify: `starter/src/test/java/cn/byteforce/seedj/test/extension/ClusterExtension.java`（如需要共享 topic 配置）
- Modify: `docs/spi/README.md`
- Modify: `docs/adr/ADR-0002-infra-spi-constitution.md`（记录迁移与消费侧）
- Modify: `domain-jdbc-postgresql/README.md`

**Interfaces:**
- Consumes: Task 5 的自动配置。
- Produces: IM 发布 `it.consume` → HR 消费并记录；`EventConsumeIT` 断言跨服务消费。

- [x] 写 HR subscriber + 记录端点 + `EventConsumeIT`。
- [x] 运行 `mvn -o -pl starter -am verify -Dit.test=EventBusIT,EventConsumeIT`（Docker 可用时）。
- [x] 更新 SPI 文档：模块表删除 `domain-eventbus-spring`，补消费组、ack/retry/DLQ、migration 说明。
- [x] 全量 `mvn -o -pl core,domain-infra-memory,domain-jdbc-postgresql,starter -am test`。
