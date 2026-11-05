# 可观测性零暴露 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 让审计、指标、链路追踪对业务代码零暴露：业务只写 `@Audited`，框架在既有调用点完成其余全部工作。

**Architecture:** 审计走 `CommandAgent` 的事务块（`CommandAudit` seam，注解只在 starter 解析）；指标复用既有 “seam 接口 + noop + optional micrometer” 模式；追踪只在 `DefaultEventPublisher` 注入 `traceparent`、在 `EventConsumerContainer.dispatch` 恢复上下文，`core` 与 adapter 不改。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Micrometer（core 已有，tracing 新增 optional）、PostgreSQL/JdbcTemplate、JUnit 5 + AssertJ + ApplicationContextRunner。

**Spec:** `docs/superpowers/specs/2026-11-05-observability-zero-exposure-design.md`

## Global Constraints

- `core` 不得 import `io.micrometer.*`、`io.opentelemetry.*`、`org.springframework.*`。
- 指标名 `seedj.<capability>.<subject>.<unit>`；tag 只允许 `action`/`resourceType`/`eventType`/`topic`/`command`/`outcome`/`status`/`mode`/`retryable`。
- 审计与命令同事务；拒绝记录不进哈希链。
- `seedj.audit.enabled` 默认 true；开启但无 `AuditStore` 或事务管理器 → 启动失败。
- 所有新 seam 提供 `noop()`；无 micrometer / micrometer-tracing 时装配不失败。

## Review Focus

- 命令在 `CommandAgent` 重试路径上是否重复审计（重试只应产生一条）。
- 审计写失败是否真的回滚命令（`AuditException` 不得被吞）。
- `traceparent` 在 `append → claim → deliver` 全程是否被改写。
- 消费端线程池复用时 MDC / span 作用域是否泄漏。
- 高基数 tag 是否混入 `resourceId` / `eventId` / `actor`。

---

### Task 1: core 审计契约

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/audit/Audited.java`
- Create: `core/src/main/java/cn/byteforce/seedj/audit/AuditErrorCode.java`
- Create: `core/src/main/java/cn/byteforce/seedj/audit/AuditException.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandAudit.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandAgent.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/eventbus/EventMessage.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandAgentAuditTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/CorePurityTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/OptionsDefaultsGateTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java`

**Interfaces:**
- Produces: `CommandAudit.accepted(Command<?>, String)`, `CommandAudit.rejected(Command<?>, String, RuntimeException)`, `CommandAgent.Options<ID>.audit()`, `EventMessage.withHeaders(Map<String,String>)`.

- [x] 写 `CommandAgentAuditTest`：accepted 在事务内被调用；命令抛异常不调用 accepted；业务拒绝调用 rejected；审计异常不回写 rejected。
- [x] `mvn -o -pl core test -Dtest=CommandAgentAuditTest` 失败。
- [x] 实现契约与 `CommandAgent` 钩子。
- [x] `mvn -o -pl core verify` 通过。

### Task 2: 指标 seam

**Files:**
- Modify: `starter/.../eventbus/wiring/EventBusMetrics.java`、`MicrometerEventBusMetrics.java`
- Create: `starter/.../cqrs/wiring/CommandMetrics.java`、`MicrometerCommandMetrics.java`、`CommandMetricsInterceptor.java`
- Create: `starter/.../audit/wiring/AuditMetrics.java`、`MicrometerAuditMetrics.java`
- Modify: `seedj-outbox-starter/.../outbox/wiring/OutboxMetrics.java`、`MicrometerOutboxMetrics.java`、`OutboxRelay.java`
- Test: `starter/src/test/.../cqrs/wiring/CommandMetricsInterceptorTest.java`、`audit/wiring/AuditMetricsTest.java`

- [x] 写指标测试（SimpleMeterRegistry 断言名字与 tag）。
- [x] 实现 seam + micrometer 实现 + relay lag。
- [x] `mvn -o -pl starter,seedj-outbox-starter -am test` 通过。

### Task 3: 审计装配（starter）

**Files:**
- Create: `starter/.../audit/wiring/{AuditProperties,AuditedCommands,AnnotationCommandAudit,DisabledCommandAudit,TransactionalAuditAppender,MetricsAuditAppender,AuditAutoConfiguration}.java`
- Create: `starter/.../cqrs/wiring/{CommandAgents,CommandAgentAutoConfiguration,SpringTransactionRunner}.java`
- Test: `starter/src/test/.../audit/wiring/AuditAutoConfigurationTest.java`、`CommandAgentsTest.java`

**Interfaces:**
- Consumes: `CommandAudit`、`AuditAppender<String>`、`AuditStore<String>`、`IamAuditSink`。
- Produces: `CommandAgents.options()`、`seedj.audit.enabled` / `seedj.audit.provider`。

- [x] 装配测试：无 store → 启动失败（`STORE_MISSING`）；`provider=memory` → 成功；`enabled=false` → `DisabledCommandAudit`。
- [x] 实现装配与 `CommandAgents`（framework interceptor 在最外层）。
- [x] `mvn -o -pl starter -am test` 通过。

### Task 4: 审计存储适配器

**Files:**
- Create: `domain-infra-memory/.../audit/memory/{InMemoryAuditStore,InMemoryAuditAutoConfiguration}.java`
- Create: `domain-jdbc-postgresql/.../audit/jdbc/{JdbcAuditStore,AuditMigrations,AuditSchemaCapability,PostgresAuditAutoConfiguration}.java`
- Test: `domain-jdbc-postgresql/src/test/.../audit/jdbc/JdbcAuditStoreContractTest.java`

- [x] 写契约测试：连续 append 序号连续；回滚不消耗序号；head 与 last_hash 一致。
- [x] 实现内存与 JDBC store + 迁移 + capability。
- [x] `mvn -o -pl domain-infra-memory,domain-jdbc-postgresql -am test` 通过。

### Task 5: 追踪

**Files:**
- Create: `starter/.../observability/wiring/{TracePropagation,MicrometerTracePropagation,ObservabilityAutoConfiguration}.java`
- Modify: `starter/.../eventbus/wiring/DefaultEventPublisher.java`、`EventConsumerContainer.java`、`EventBusAutoConfiguration.java`
- Modify: `starter/pom.xml`（`micrometer-tracing` optional；`micrometer-tracing-test` test）
- Test: `starter/src/test/.../observability/wiring/TracePropagationTest.java`、`EventBusTracePropagationTest.java`

- [x] 写测试：发布注入 `traceparent`；已有 header 不覆盖；消费端 span 的 traceId 与父一致；无 tracing 依赖时 noop。
- [x] 实现 seam 与两处接线。
- [x] `mvn -o -pl starter -am test` 通过。

### Task 6: 迁移与文档

**Files:**
- Modify: `starter/.../demo/hr/config/HrConfiguration.java`（改用 `CommandAgents.options()`）
- Modify: `starter/src/test/.../demo/hr/HrWiringTest.java`（补 `CommandAgents` bean）
- Modify: `docs/spi/README.md`（`seedj.audit.*` 配置 + tag 白名单）

- [x] `mvn -o -pl starter -am verify`、`mvn -o -pl domain-jdbc-postgresql -am verify` 全绿。
