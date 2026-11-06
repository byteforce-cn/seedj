# 命令运行时与 HTTP 契约实施计划（P2）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有模块内交付 L2 操作契约的可执行纵切：幂等命令运行时、内存/PostgreSQL 命令存储、`POST /commands/{commandType}`、`GET /commands/{id}`、`GET /objects/{objectType}/{id}`（含 `as_of` / `recorded_as_of` / `min_version`）与统一错误信封。

**Architecture:** 纯 Java 语义放 `core`（`CommandRecord` / `CommandSubmission` / `CommandReceipt` / `IdempotencyFingerprint` / `CommandStore` SPI / `TemporalCommandAgent` / `CommandRuntime` / `CommandCatalog`）；内存实现放 `seedj-infra-local`，PostgreSQL 表与实现放 `seedj-jdbc-postgresql`；HTTP 面、错误处理器与装配放 `seedj-biz-starter`。命令运行时按“幂等记录 → 锁 → 事务（封口/追加 + 事件 + 命令终态）→ 解锁”的顺序执行，拒绝路径在事务外补写终态。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、AssertJ、Spring MVC / MockMvc、Spring `JdbcTemplate`、Jackson 3（`tools.jackson`）、Testcontainers（`postgres:17-alpine`）、PostgreSQL 16+。

**Spec:** `docs/superpowers/specs/2026-11-05-contract-conformance-design.md`（本计划只覆盖 §9 的 P2；P3–P6 另出计划）

## Global Constraints

- 产品代码（`src/main/java`、`src/main/resources`、所有 `pom.xml`）不得出现规范名（不区分大小写），**唯一例外**：错误码 `EIS-{3位}-{3位}`、Schema URI `eis://…`、DDL 域类型 `eis_id` / `eis_version` / `eis_timestamp`。
- 不新增 Maven module、不新增仓库根目录；新代码只进 `core`、`seedj-infra-local`、`seedj-jdbc-postgresql`、`seedj-biz-starter`、`seedj-it`、`docs/conformance/`。
- `core` 零 Spring、零 seedj 模块依赖；`seedj-infra-local` 与 `seedj-jdbc-postgresql` 互不依赖。
- 所有时间字段为 UTC、微秒精度（nano 为 1000 的整数倍）；取时只经 `cn.byteforce.seedj.core.clock.Clock`。
- 标识为 UUIDv7；命令 `id`、事件 `id`、`issuer`、`targets`、`locator` 的线格式都是 UUID。
- 每个新 public 类型带中文 javadoc，含 `@author ByteForce` 与 `@since 1.0.0`（沿用仓库风格）。
- 每个任务结束时该模块 `mvn -o -B -pl <module> -am test` 必须绿；提交信息用 `feat:` / `test:` / `refactor:` / `docs:` 前缀。
- 不修改既有 `CommandAgent`（事件溯源路径）；`TemporalCommandAgent` 是并列实现。
- 错误信封固定 `{code,message,details?}`；`reason_code` 放 `details`；`EIS-409-003` 必须带 `Retry-After`。
- 命令业务拒绝的 HTTP 响应是 `{command_id,status:"rejected",reason,code}`（400）；幂等冲突是 `{idempotency_key,original_command_id,original_result}`（409）。这是 `02-operation-contract/command.openapi.yaml` 的冻结形状。

## Review Focus

- **同幂等键并发提交**：两个请求同时看不到既有记录，唯一索引只能放行一个；输者必须回读并回放/报冲突，不能新建第二条命令。
- **拒绝路径的持久化**：对象追加回滚后，`rejected` 命令记录仍必须可查；同一幂等键重放必须返回原拒绝，不能重新执行。
- **`min_version` 与历史参数组合**：带 `as_of`/`recorded_as_of` 且返回版本低于 `min_version` 时同样 `409 + Retry-After`，不得返回旧版本冒充当前状态。
- **半开区间边界**：`t == valid_to` 或 `t == superseded_at` 必须命中后继版本；控制器不得在 Java 侧做区间过滤。
- **错误形状分叉**：命令业务拒绝、幂等冲突、401/403、查询 404/409 必须分别回冻结形状；不能让一个全局异常处理器把它们压成同一种信封。

---

### Task 1: 命令契约类型与幂等指纹

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandStatus.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRecord.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandReceipt.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandSubmission.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/IdempotencyFingerprint.java`
- Create: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandRecordTest.java`
- Create: `core/src/test/java/cn/byteforce/seedj/cqrs/IdempotencyFingerprintTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `enum CommandStatus { PENDING, ACCEPTED, REJECTED }`，`String wireName()` 返回小写，`static CommandStatus fromWire(String)`。
  - `record CommandRecord(UUID id, String type, Instant issuedAt, UUID issuer, Map<String,Object> payload, String idempotencyKey, String payloadFingerprint, CommandStatus status, String rejectionCode, String rejectionReason, List<UUID> eventIds, UUID locator, Long version, Instant createdAt)`；工厂 `static CommandRecord pending(UUID id, String type, Instant issuedAt, UUID issuer, Map<String,Object> payload, String idempotencyKey, String payloadFingerprint, Instant createdAt)`、实例方法 `accepted(UUID locator, long version, List<UUID> eventIds)`、`rejected(ContractErrorCode code, String reason)`、`isTerminal()`。
  - `record CommandReceipt(UUID commandId, CommandStatus status, List<UUID> eventIds, UUID locator, Long version, String code, String reason)`；`static CommandReceipt from(CommandRecord)`。
  - `record CommandSubmission(String type, Map<String,Object> payload, String idempotencyKey, List<UUID> targets, UUID issuer)`。
  - `final class IdempotencyFingerprint`；`static String of(CommandSubmission)` 与 `static String of(String type, Map<String,Object> payload, List<UUID> targets)`。

- [ ] **Step 1: 写失败测试**

```java
// CommandRecordTest
@Test
void pendingRecordHasNoTerminalFields() {
    CommandRecord record = pending();
    assertThat(record.status()).isEqualTo(CommandStatus.PENDING);
    assertThat(record.locator()).isNull();
    assertThat(record.version()).isNull();
    assertThat(record.rejectionCode()).isNull();
}

@Test
void acceptedRequiresLocatorAndVersion() {
    CommandRecord record = pending().accepted(UUID.randomUUID(), 1L, List.of());
    assertThat(record.status()).isEqualTo(CommandStatus.ACCEPTED);
    assertThat(record.version()).isEqualTo(1L);
    assertThatThrownBy(() -> new CommandRecord(..., CommandStatus.ACCEPTED, null, null,
            List.of(), null, null, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("locator");
}

@Test
void rejectedRequiresCodeAndReasonAndWireNamesAreLowercase() {
    CommandRecord record = pending().rejected(ContractErrorCode.TEMPORAL_VIOLATION, "bad range");
    assertThat(record.status().wireName()).isEqualTo("rejected");
    assertThat(record.rejectionCode()).isEqualTo("EIS-400-003");
    assertThatThrownBy(() -> new CommandRecord(..., CommandStatus.REJECTED, "EIS-400-003",
            null, List.of(), null, null, Instant.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
}

// IdempotencyFingerprintTest
@Test
void matchesReferenceKernelForSameInput() {
    Map<String, Object> payload = Map.of(
            "employee_id", "01900000-0000-7000-8000-000000000101",
            "name", "Alice");
    assertThat(IdempotencyFingerprint.of("hr.employee.onboard", payload,
            List.of(UUID.fromString("01900000-0000-7000-8000-000000000201"))))
            .isEqualTo("fe097238cca38f8806ba9fe20856a001b2cc0ada9bc319df64f9048d6fd33cb4");
}

@Test
void ignoresPayloadKeyOrderButIncludesTargetOrder() {
    ... // 两个 LinkedHashMap 键序不同 → 同摘要；targets 顺序互换 → 不同摘要
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest='CommandRecordTest,IdempotencyFingerprintTest'`
Expected: FAIL，编译错误（类不存在）。

- [ ] **Step 3: 实现四个类型与指纹**

`CommandStatus` 的 `wireName()` 用 `name().toLowerCase(Locale.ROOT)`；`fromWire` 只接受三个小写值，其他抛 `IllegalArgumentException`。

`CommandRecord` 构造期校验：
- `id/type/issuedAt/issuer/payload/idempotencyKey/payloadFingerprint/status/createdAt` 非空；
- `type` 匹配 `^[a-z][a-z0-9_.]*$`，`idempotencyKey` 非空白且长度 ≤ 255；
- `payloadFingerprint` 匹配 `^[0-9a-f]{64}$`；
- `PENDING` 不得带 `locator/version/rejectionCode/rejectionReason`；
- `ACCEPTED` 必须有 `locator` 与 `version >= 1`，不得带拒绝字段；
- `REJECTED` 必须有 `rejectionCode`（匹配 `^EIS-\d{3}-\d{3}$`）与非空 `rejectionReason`，不得带 `locator/version`；
- 时间统一 `truncatedTo(ChronoUnit.MICROS)`；`payload` 与 `eventIds` 不可变拷贝（允许 payload 值为 `null`，用 `LinkedHashMap` 包装，不要用 `Map.copyOf`）。

`CommandReceipt.from(CommandRecord)` 直接映射字段；`PENDING` 允许 `locator/version/code/reason` 全空。

`IdempotencyFingerprint` 用 `JsonMapper.builder().build()` 把 `{type, payload, targets}` 转成 `JsonNode`，再走 `CanonicalJsonHelper.pythonDumps(node)` 与 `HashesHelper.sha256Hex(text)`——与参考内核的 `json.dumps(sort_keys=True, ensure_ascii=False)` 对齐。

- [ ] **Step 4: 更新公开表面白名单并运行**

在 `SpiSurfaceTest.EXTRA_ALLOWED` 的 `cn.byteforce.seedj.cqrs` 列表加入：`CommandReceipt`、`CommandRecord`、`CommandStatus`、`CommandSubmission`、`IdempotencyFingerprint`。

Run: `mvn -o -B -pl core test -Dtest='CommandRecordTest,IdempotencyFingerprintTest,SpiSurfaceTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs core/src/test/java/cn/byteforce/seedj/cqrs core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java
git commit -m "feat: add command contract types and idempotency fingerprint"
```

---

### Task 2: `CommandStore` SPI 与契约测试套件

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java`
- Create: `core/src/test/java/cn/byteforce/seedj/cqrs/spi/CommandStoreContractTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java`

**Interfaces:**
- Consumes: `CommandRecord`、`CommandStatus`、`ContractErrorCode`。
- Produces:
  - `interface CommandStore`：
    - `Optional<CommandRecord> find(UUID id)`
    - `Optional<CommandRecord> findByIdempotencyKey(String idempotencyKey)`
    - `boolean insert(CommandRecord record)`——`id` 或 `idempotencyKey` 已存在返回 `false`，不覆盖既有行；
    - `void complete(CommandRecord record)`——只允许从 `PENDING` 进入终态；同一终态重复调用幂等；记录不存在抛 `IllegalStateException`。

- [ ] **Step 1: 写失败测试（抽象契约套件）**

```java
public abstract class CommandStoreContractTest {
    protected abstract CommandStore store();
    protected abstract void reset();

    @Test
    void insertsAndFindsByIdAndKey() {
        CommandRecord record = pending("k-1");
        assertThat(store().insert(record)).isTrue();
        assertThat(store().find(record.id())).contains(record);
        assertThat(store().findByIdempotencyKey("k-1")).contains(record);
    }

    @Test
    void duplicateKeyIsRejectedWithoutOverwriting() {
        CommandRecord first = pending("k-2");
        CommandRecord second = pending("k-2");
        assertThat(store().insert(first)).isTrue();
        assertThat(store().insert(second)).isFalse();
        assertThat(store().findByIdempotencyKey("k-2"))
                .get().extracting(CommandRecord::id).isEqualTo(first.id());
    }

    @Test
    void completesAcceptedWithLocatorVersionAndEvents() {
        CommandRecord pending = pending("k-3");
        store().insert(pending);
        UUID locator = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        store().complete(pending.accepted(locator, 2L, List.of(eventId)));
        assertThat(store().find(pending.id())).get()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(CommandStatus.ACCEPTED);
                    assertThat(row.locator()).isEqualTo(locator);
                    assertThat(row.version()).isEqualTo(2L);
                    assertThat(row.eventIds()).containsExactly(eventId);
                });
    }

    @Test
    void completesRejectedWithCodeAndReasonAndIsIdempotent() { ... }

    @Test
    void completingMissingRecordFails() { ... }

    @Test
    void payloadSurvivesRoundTrip() { ... } // 嵌套 Map/数组/数字/字符串
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest=CommandStoreContractTest`
Expected: FAIL，编译错误（`CommandStore` 不存在）。

- [ ] **Step 3: 实现 SPI**

Javadoc 写明：`insert` 必须由数据库唯一约束或等价同步保证；`complete` 只改终态字段，不得回写 payload/指纹；实现必须在调用方事务内工作（对象行、命令记录、审计/事件同事务）。

- [ ] **Step 4: 更新白名单并编译契约套件**

`SpiSurfaceTest.EXTRA_ALLOWED` 的 `cn.byteforce.seedj.cqrs.spi` 加入 `CommandStore`。

Run: `mvn -o -B -pl core test -Dtest='SpiSurfaceTest'`
Expected: PASS（抽象契约套件不执行）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs/spi/CommandStore.java core/src/test/java/cn/byteforce/seedj/cqrs/spi/CommandStoreContractTest.java core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java
git commit -m "feat: add command store spi and contract suite"
```

---

### Task 3: 内存 `CommandStore`

**Files:**
- Create: `seedj-infra-local/src/main/java/cn/byteforce/seedj/cqrs/memory/InMemoryCommandStore.java`
- Create: `seedj-infra-local/src/test/java/cn/byteforce/seedj/cqrs/memory/InMemoryCommandStoreContractTest.java`

**Interfaces:**
- Consumes: `CommandStore`、`CommandRecord`。
- Produces: `final class InMemoryCommandStore implements CommandStore`，带 `void clear()` 供测试。

- [ ] **Step 1: 写失败测试**

```java
class InMemoryCommandStoreContractTest extends CommandStoreContractTest {
    private final InMemoryCommandStore store = new InMemoryCommandStore();

    @Override protected CommandStore store() { return store; }
    @Override protected void reset() { store.clear(); }

    @Test
    void sameTerminalCompletionIsIdempotent() { ... }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryCommandStoreContractTest`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现**

用 `ConcurrentHashMap<UUID, CommandRecord>` 与 `ConcurrentHashMap<String, UUID>`；`insert`/`complete` 用同一把锁（`synchronized`）保证“检查 + 写入”原子。`complete` 幂等规则：现有记录已是同一终态（`status`、`locator`、`version`、`rejectionCode`、`rejectionReason`、`eventIds` 全部相等）→ 直接返回；否则抛 `IllegalStateException`。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryCommandStoreContractTest`
Expected: PASS（契约套件全部用例）。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-local/src/main/java/cn/byteforce/seedj/cqrs/memory seedj-infra-local/src/test/java/cn/byteforce/seedj/cqrs/memory
git commit -m "feat: add in-memory command store"
```

---

### Task 4: `TemporalCommandAgent`、`CommandRuntime` 与事件信封

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/event/CommandEvent.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/TemporalCommandAgent.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandCatalog.java`
- Create: `seedj-infra-local/src/test/java/cn/byteforce/seedj/cqrs/TemporalCommandAgentTest.java`
- Modify: `core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java`

**Interfaces:**
- Consumes: `CommandStore`、`TemporalStore`、`TransactionRunner`、`LockProvider`、`Clock`、`IdGenerator<UUID>`、`OutboxWriter`、`CommandRecord`、`CommandSubmission`、`CommandReceipt`、`IdempotencyFingerprint`。
- Produces:
  - `record CommandEvent(UUID id, String type, Instant occurredAt, Instant recordedAt, UUID actor, List<UUID> subjects, Map<String,Object> payload, String idempotencyKey, int version)`；构造期校验 `occurredAt <= recordedAt`、`version >= 1`、类型模式、幂等键非空。
  - `final class CommandRuntime`，嵌套 `record Options(TransactionRunner transactionRunner, LockProvider lockProvider, Duration lockTtl, Clock clock, IdGenerator<UUID> idGenerator, CommandStore commandStore, TemporalStore temporalStore, OutboxWriter outboxWriter)`（`outboxWriter` 可为 `null`）；嵌套 `interface Work { String objectType(); UUID targetId(); void validate(); AppendRequest append(Optional<TemporalRow> current, Instant now, UUID actor); default List<CommandEvent> events(AppendResult result, Instant now, UUID actor) { return List.of(); } }`。
  - `CommandRuntime#execute(CommandSubmission, Work)`、`#newId()`、`#now()`。
  - `abstract class TemporalCommandAgent<C>`：构造器 `(CommandRuntime runtime, String commandType, String objectType, Class<C> payloadType)`；`public final CommandReceipt execute(CommandSubmission)`；`public final String commandType()`；`protected void validate(C)` 默认空；`protected abstract UUID targetId(C)`；`protected abstract AppendRequest append(C, Optional<TemporalRow> current, Instant now, UUID actor)`；`protected List<CommandEvent> events(C, AppendResult result, Instant now, UUID actor)` 默认空；`protected final UUID newId()`、`protected final Instant now()`。
  - `final class CommandCatalog`：`CommandCatalog(List<TemporalCommandAgent<?>> agents)`、`CommandReceipt execute(CommandSubmission)`、`Optional<TemporalCommandAgent<?>> find(String type)`、`Set<String> types()`；未知类型抛 `ContractException(EIS-400-001)`。

- [ ] **Step 1: 写失败测试**

```java
class TemporalCommandAgentTest {
    private final TestClock clock = new TestClock(T0);
    private final InMemoryTemporalStore objects = new InMemoryTemporalStore(clock);
    private final InMemoryCommandStore commands = new InMemoryCommandStore();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final RecordingLock lock = new RecordingLock();
    private final CommandRuntime runtime = new CommandRuntime(new CommandRuntime.Options(
            TransactionRunner.direct(), lock, Duration.ofSeconds(30), clock,
            new Uuid7IdGenerator(clock), commands, objects, outbox));
    private final ProbeAgent agent = new ProbeAgent(runtime);
    private final CommandCatalog catalog = new CommandCatalog(List.of(agent));

    @Test
    void acceptedAppendsObjectAndCompletesCommand() { ... }
    // 断言：receipt.status=ACCEPTED、locator=ID、version=1、objects.current 命中；
    // commands.find(receipt.commandId()).status=ACCEPTED；archive 里有 event。

    @Test
    void sameKeySamePayloadReplaysWithoutSecondAppend() { ... }
    // 两次 execute → 同一 commandId，objects.history 只有 1 条，outbox 只有 1 条。

    @Test
    void sameKeyDifferentPayloadConflicts() { ... }
    // ContractException.code=IDEMPOTENCY_CONFLICT，details 含 original_command_id。

    @Test
    void rejectedCommandPersistsRejectionAndRollsBackAppend() { ... }
    // 让 append 抛 TEMPORAL_VIOLATION；objects.find 为空；commands 里记录 REJECTED；
    // 同键重放仍返回同一拒绝且不再次 append。

    @Test
    void pendingReplayDoesNotExecuteAgain() { ... }
    // 先手工 insert 一条同键同指纹 PENDING；execute 返回 PENDING，objects.history 为空。

    @Test
    void lockKeyIsObjectTypeAndTarget() { ... }
    // lock.keys 包含 "probe:<uuid>"。

    @Test
    void eventEnvelopeUsesContractWireFormat() { ... }
    // outbox.records.get(0).destination = "probe.updated"；payload JSON 含
    // "occurred_at"/"recorded_at"/"idempotency_key"/"subjects"，且 messageId=event id。
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=TemporalCommandAgentTest`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现运行时**

`CommandRuntime.execute` 顺序：
1. 计算 `IdempotencyFingerprint`；按 `submission.type()` 与 `Work` 校验 `objectType`/`targetId` 非空。
2. `findByIdempotencyKey`：存在且指纹一致 → 返回 `CommandReceipt.from(existing)`；指纹不一致 → 抛 `IDEMPOTENCY_CONFLICT`，`details` 含 `idempotency_key`、`original_command_id`、`original_result`（`status`、`locator`、`version`、`event_ids`、`code`、`reason`）。
3. 不存在 → `commands.insert(pending)`；若返回 `false`，回读后按上一步处理。
4. `work.validate()`；`ContractException` → 事务外 `commands.complete(pending.rejected(...))`，把 `command_id` 并入 `details` 后抛出。
5. `lockProvider.acquire(objectType + ":" + targetId, lockTtl)`（事务外）。
6. `transactionRunner.inTransaction`：读 `temporalStore.current` → `work.append` → `temporalStore.append` → `work.events` → 逐条写 outbox → `commands.complete(pending.accepted(locator, version, eventIds))` → 返回记录。
7. 事务内 `ContractException` → 事务回滚；事务外补写 `rejected`，再抛出（补写失败作为 suppressed 挂在原异常上）。
8. 技术异常（`InfrastructureException` 等）不补写拒绝，保持 `PENDING`，由 HTTP 层回 503；`CommandReceipt.from(PENDING)` 由控制器转 503。

`CommandRuntime` 的 outbox 写入把 `CommandEvent` 手工映射为 snake_case 的 `LinkedHashMap`（`id/type/occurred_at/recorded_at/actor/subjects/payload/idempotency_key/version`），`contentType` 用 `eis://core/event/v1`（§2.1 例外），`destination=event.type()`，`partitionKey=首个 subject`。

`TemporalCommandAgent` 用静态 `JsonMapper` 做 `convertValue(payload, payloadType)`；失败转 `ContractException(INVALID_PAYLOAD, "invalid payload for ...")`。

- [ ] **Step 4: 更新白名单并运行**

`SpiSurfaceTest` 的 `cqrs` 加入 `CommandCatalog`、`CommandRuntime`、`TemporalCommandAgent`。

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=TemporalCommandAgentTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/cqrs core/src/main/java/cn/byteforce/seedj/domain/event/CommandEvent.java core/src/test/java/cn/byteforce/seedj/SpiSurfaceTest.java seedj-infra-local/src/test/java/cn/byteforce/seedj/cqrs/TemporalCommandAgentTest.java
git commit -m "feat: add temporal command runtime"
```

---

### Task 5: PostgreSQL 命令表与存储

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/cqrs/jdbc/CommandMigrations.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/cqrs/jdbc/PostgresCommandStore.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/cqrs/jdbc/CommandSchemaCapability.java`
- Create: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/cqrs/jdbc/PostgresCommandStoreContractTest.java`
- Create: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/cqrs/jdbc/TemporalCommandTransactionTest.java`

**Interfaces:**
- Consumes: `CommandStore`、`CommandRecord`、`JdbcMigrationRunner`、`TemporalStore`、`CommandRuntime`。
- Produces: `final class PostgresCommandStore implements CommandStore`；`CommandSchemaCapability`（`NAME="postgres-command"`、`MIGRATION_SCOPE="command"`、`getOrder()=130`）；`CommandMigrations.all()` 包私有。

- [ ] **Step 1: 写失败测试**

```java
@Testcontainers
class PostgresCommandStoreContractTest extends CommandStoreContractTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = ...postgres:17-alpine...;
    @BeforeAll static void init() { ... new CommandSchemaCapability(new JdbcMigrationRunner(ds)).initialize(); }
    @Override protected CommandStore store() { return store; }
    @Override protected void reset() { jdbc.execute("TRUNCATE command_record"); }
}

@Testcontainers
class TemporalCommandTransactionTest {
    // 真事务 + PostgresTemporalStore + PostgresCommandStore + ProbeAgent
    @Test void acceptedWritesObjectAndCommandInOneTransaction() { ... }
    @Test void rejectedLeavesNoObjectButKeepsRejectedCommand() { ... }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest='PostgresCommandStoreContractTest,TemporalCommandTransactionTest'`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现 DDL 与存储**

`CommandMigrations` V1（scope 由 `CommandSchemaCapability` 提供）：

```sql
CREATE TABLE IF NOT EXISTS command_record (
    id                  UUID         PRIMARY KEY,
    type                VARCHAR(128) NOT NULL,
    issued_at           TIMESTAMPTZ(6) NOT NULL,
    issuer              UUID         NOT NULL,
    payload             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key     VARCHAR(255) NOT NULL,
    payload_fingerprint CHAR(64)     NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    rejection_code      VARCHAR(16),
    rejection_reason    TEXT,
    event_ids           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    locator             UUID,
    version             BIGINT,
    created_at          TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT uq_command_record_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_command_record_status CHECK (status IN ('pending','accepted','rejected')),
    CONSTRAINT ck_command_record_fingerprint CHECK (payload_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_command_record_accepted CHECK (
        status <> 'accepted' OR (locator IS NOT NULL AND version IS NOT NULL AND version >= 1)),
    CONSTRAINT ck_command_record_rejected CHECK (
        status <> 'rejected' OR (rejection_code IS NOT NULL AND rejection_reason IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_command_record_status ON command_record (status, created_at);
```

`PostgresCommandStore`：
- `insert` 用 `INSERT ... ON CONFLICT DO NOTHING`，返回更新行数 == 1；
- `find` / `findByIdempotencyKey` 把 `payload`、`event_ids` 用 `JsonMapper` 反序列化（`Map<String,Object>`、`List<UUID>`）；
- `complete` 用 `UPDATE ... WHERE id = ? AND status = 'pending'`；0 行时回读，若已是同一终态则幂等返回，否则抛 `IllegalStateException`；
- 所有 JSONB 参数用 `?::jsonb`（与既有 `JdbcIamUserRepository` 一致）。

`TemporalCommandTransactionTest` 用 `TransactionTemplate` 包 `CommandRuntime` 的 `TransactionRunner`；建 `probe_object` 表并注册 `TemporalTables`；断言提交后对象与命令都在、拒绝后对象不在但命令是 `rejected`。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest='PostgresCommandStoreContractTest,TemporalCommandTransactionTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/cqrs/jdbc seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/cqrs/jdbc
git commit -m "feat: add postgres command store"
```

---

### Task 6: 契约错误信封与 401/403 改造

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/web/ContractErrorHandler.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/JsonAuthenticationEntryPoint.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/JsonAccessDeniedHandler.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security/BizSecurityExceptionHandler.java`
- Modify: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/biz/security/JsonSecurityResponseTest.java`
- Create: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/biz/web/ContractErrorHandlerTest.java`

**Interfaces:**
- Consumes: `ContractException`、`ContractErrorCode`、`ApiError`、`InfrastructureException`。
- Produces: `@RestControllerAdvice @Order(HIGHEST_PRECEDENCE + 10) class ContractErrorHandler`，处理：
  - `ContractException` → `code.httpStatus()`，body `{code,message,details}`；`reasonCode` 并入 `details.reason_code`；`READ_MODEL_NOT_READY` 加 `Retry-After: 1`。
  - `HttpMessageNotReadableException`、`MethodArgumentTypeMismatchException`、`MissingServletRequestParameterException` → 400 `EIS-400-001`。
  - `InfrastructureException`、`DataAccessException` → 503 `EIS-503-001`（不泄露内部消息）。
  - 其他 `Exception` → 500 `EIS-500-001`，`details.traceId` 为随机 UUID，日志记录原始异常。

- [ ] **Step 1: 写失败测试**

```java
// ContractErrorHandlerTest：standalone MockMvc + 一个抛异常的测试控制器
@Test void contractErrorUsesFrozenEnvelope() { ... } // 404 → $.code=EIS-404-001
@Test void readModelNotReadyAddsRetryAfterAndReasonCode() { ... } // 409，header=1，details.reason_code
@Test void malformedBodyIs400InvalidPayload() { ... }
@Test void infrastructureFailureIs503WithoutInternalMessage() { ... }
@Test void unexpectedExceptionIs500WithTraceId() { ... }

// JsonSecurityResponseTest 改断言
assertThat(body).containsEntry("code", "EIS-401-001");
assertThat(body).containsEntry("code", "EIS-403-001");
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest='ContractErrorHandlerTest,JsonSecurityResponseTest'`
Expected: FAIL（新信封字段不存在）。

- [ ] **Step 3: 实现处理器与改造**

- `ContractErrorHandler` 的 500 分支用 `UUID.randomUUID().toString()` 作为 traceId；不要返回异常消息。所有 `ContractException` 的 `details.reason_code` 优先取 `reasonCode`，为空时回落到 `code().getCode()`（`EIS-409-003` 因此稳定出现）。
- `JsonAuthenticationEntryPoint`：先写 `WWW-Authenticate`，再写 `new ApiError(ContractErrorCode.UNAUTHENTICATED.getCode(), "authentication required", Map.of())`；无效凭证仍用 401，消息 `"invalid credentials"`。
- `JsonAccessDeniedHandler`：`new ApiError(FORBIDDEN.getCode(), "access denied", Map.of("reason", "access denied"))`。
- `BizSecurityExceptionHandler`：同样用 `ContractErrorCode` 的 `EIS-401-001` / `EIS-403-001`；保留 `SecurityDenialAudit` 调用（审计仍用 `SecurityErrorCode`）。
- 现有 `JsonSecurityResponseTest` 全部改断言为 `code`，并补一条“403 必带 details.reason”。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest='ContractErrorHandlerTest,JsonSecurityResponseTest,BizSecurityAutoConfigurationTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/web seedj-biz-starter/src/main/java/cn/byteforce/seedj/biz/security seedj-biz-starter/src/test/java/cn/byteforce/seedj/biz
git commit -m "feat: unify contract error envelope"
```

---

### Task 7: 命令 HTTP 面

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/web/CommandRequest.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/web/CommandController.java`
- Create: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/web/CommandControllerTest.java`

**Interfaces:**
- Consumes: `CommandCatalog`、`CallerProvider`、`CommandSubmission`、`CommandReceipt`、`ContractErrorHandler`。
- Produces:
  - `record CommandRequest(String type, Map<String,Object> payload, String idempotencyKey, List<UUID> targets)`，类上 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`。
  - `@RestController final class CommandController`：构造器 `(CommandCatalog catalog, CommandStore commandStore, CallerProvider callers)`。
    - `@PostMapping("/commands/{commandType}") ResponseEntity<?> submit(@PathVariable String commandType, @RequestBody CommandRequest request)`
    - `@GetMapping("/commands/{id}") ResponseEntity<?> status(@PathVariable UUID id)`

- [ ] **Step 1: 写失败测试**

```java
class CommandControllerTest {
    // 用 InMemoryTemporalStore + InMemoryCommandStore + ProbeAgent + CommandCatalog
    // MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ContractErrorHandler())

    @Test void acceptsCommandWith202AndLocatorVersion() { ... }
    @Test void replaysSameKeyWithOriginalResult() { ... }
    @Test void sameKeyDifferentPayloadIs409WithOriginalCommand() { ... }
    @Test void businessRejectionIs400WithFrozenBody() { ... }
    @Test void getCommandReturnsTerminalStatus() { ... }
    @Test void unknownCommandTypeIs400 () { ... }
    @Test void missingIdempotencyKeyIs400() { ... }
    @Test void callerWithoutUuidIssuerIs401() { ... }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=CommandControllerTest`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现控制器**

`submit`：
1. `request.type()` 必须等于 `commandType`（缺失或不一致 → `INVALID_PAYLOAD`）；
2. `payload` 必须非空对象，`idempotency_key` 非空白；
3. `CallerProvider.find()` 为空或 `caller.id()` 不是 UUID → `UNAUTHENTICATED`；
4. `catalog.execute(new CommandSubmission(...))`；
5. `ACCEPTED` → 202，body `{command_id,status:"accepted",event_ids,locator,version}`；
6. `REJECTED` → 400，body `{command_id,status:"rejected",reason,code}`；
7. `PENDING` → `ContractException(UNAVAILABLE, "command is still pending")`；
8. `IDEMPOTENCY_CONFLICT` → 409，body 直接取 `details` 的 `{idempotency_key,original_command_id,original_result}`；
9. 其他 `ContractException` → 400 `{command_id(if present),status:"rejected",reason,code}`；`UNAUTHENTICATED`/`UNAVAILABLE` 原样抛出交给全局处理器。

`status`：`commandStore.find(id)` 为空 → `OBJECT_NOT_FOUND`；否则 200 `{command_id,status,event_ids,reason,code,issued_at}`（`status` 用小写线格式）。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=CommandControllerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/web seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/web
git commit -m "feat: add command http api"
```

---

### Task 8: 对象时点查询 HTTP 面

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/ObjectQueryResponse.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/ObjectQueryController.java`
- Create: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/domain/web/ObjectQueryControllerTest.java`

**Interfaces:**
- Consumes: `TemporalStore`、`TemporalRow`、`TemporalQuery`、`ContractErrorHandler`。
- Produces:
  - `record ObjectQueryResponse(String objectType, UUID id, long version, Instant validFrom, Instant validTo, Instant recordedAt, Instant supersededAt, Map<String,Object> data)`，类上 `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)`；`static ObjectQueryResponse from(TemporalRow)`。
  - `@RestController final class ObjectQueryController`：构造器 `(TemporalStore store)`；`@GetMapping("/objects/{objectType}/{id}") ObjectQueryResponse find(@PathVariable String objectType, @PathVariable UUID id, @RequestParam(value="as_of",required=false) String asOf, @RequestParam(value="recorded_as_of",required=false) String recordedAsOf, @RequestParam(value="min_version",required=false) Long minVersion)`。

- [ ] **Step 1: 写失败测试**

```java
@Test void returnsCurrentObjectWithSnakeCaseData() { ... } // $.object_type, $.valid_from, $.data.name
@Test void asOfUsesHalfOpenBounds() { ... } // 在 valid_to 边界命中后继
@Test void recordedAsOfReturnsHistoricalBelief() { ... }
@Test void missingObjectIs404() { ... }
@Test void minVersionNotReadableIs409WithRetryAfter() { ... } // header=1, details.reason_code
@Test void invalidTimestampIs400() { ... }
@Test void unknownObjectTypeIs400WithMessageContainingType() { ... }
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=ObjectQueryControllerTest`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现控制器**

`Instant.parse` 解析两个时点；`DateTimeParseException` → `INVALID_PAYLOAD`；`minVersion != null && minVersion < 1` → `INVALID_PAYLOAD`。查询直接交给 `TemporalStore.find`，不在 Java 侧过滤。`ContractException(READ_MODEL_NOT_READY)` 由 `ContractErrorHandler` 加 `Retry-After`。`IllegalArgumentException`（未知对象类型）转 `INVALID_PAYLOAD`，消息保留类型名。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=ObjectQueryControllerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web seedj-biz-starter/src/test/java/cn/byteforce/seedj/domain/web
git commit -m "feat: add object query http api"
```

---

### Task 9: 自动配置、OpenAPI 工件与阶段验收

**Files:**
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Modify: `seedj-biz-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `seedj-biz-starter/src/test/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfigurationTest.java`
- Create: `docs/conformance/api/command.openapi.yaml`（从规范冻结文件复制）
- Create: `docs/conformance/api/query.openapi.yaml`（从规范冻结文件复制）
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ApiAlignmentTest.java`
- Create: `docs/conformance/runs/2026-11-05-p2.md`

**Interfaces:**
- Consumes: 前九个任务的全部类型与 `DataSource` / `JdbcTemplate` / `TemporalTables`。
- Produces: `TemporalCommandAutoConfiguration`，配置键：
  - `seedj.command.enabled`（默认 true）、`seedj.command.provider`（默认：有 `JdbcTemplate` 时 `postgres`，否则 `memory`）；
  - `seedj.object.provider`（默认：有 `JdbcTemplate` 且有 `TemporalTables` 时 `postgres`，否则 `memory`）；
  - 默认 bean：`Clock`、`IdGenerator<UUID>`（bean 名 `commandIdGenerator`）、`CommandStore`、`TemporalStore`、`CommandRuntime`、`CommandCatalog`、`CommandController`、`ObjectQueryController`、`PostgresCommandStore`/`TemporalSchemaCapability`/`CommandSchemaCapability`（PG 路径）。

- [ ] **Step 1: 写失败测试**

```java
class TemporalCommandAutoConfigurationTest {
    @Test void memoryDefaultsProvideRuntimeCatalogAndControllers() { ... }
    @Test void postgresProviderWithoutDataSourceFailsFast() { ... }
    @Test void objectPostgresProviderWithoutTablesFailsFast() { ... }
    @Test void commandDisabledSwitchBacksOffRuntimeAndControllers() { ... }
    @Test void collectsAgentBeansIntoCatalog() { ... }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=TemporalCommandAutoConfigurationTest`
Expected: FAIL，编译错误。

- [ ] **Step 3: 实现装配**

- `@AutoConfiguration(afterName = "cn.byteforce.seedj.biz.security.BizSecurityAutoConfiguration", after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})`
- `@ConditionalOnBean(BizEnabledMarker.class)`、`@ConditionalOnProperty(prefix="seedj.command", name="enabled", havingValue="true", matchIfMissing=true)`
- `@Bean @ConditionalOnMissingBean` 逐项提供；`CommandStore` / `TemporalStore` 的 provider 选择用 `Environment` + `ObjectProvider<JdbcTemplate>`（provider 为 `postgres` 但依赖缺失时抛 `IllegalStateException`，不静默降级）。
- `TemporalTables`：若容器里有 `TemporalTable` bean 且没有 `TemporalTables` bean，收集并创建。
- PG 路径注册 `TemporalSchemaCapability` 与 `CommandSchemaCapability`；内存路径不注册迁移。
- Web 控制器放在嵌套 `@Configuration(proxyBeanMethods=false) @ConditionalOnWebApplication(type=SERVLET)` 中，`@ConditionalOnMissingBean`；`CommandController` 注入 `CommandCatalog`、`CommandStore`，`CallerProvider` 缺失时用 `Optional::empty`（请求最终 401）。
- `CommandCatalog` 收集 `ObjectProvider<TemporalCommandAgent<?>>`；重复 `commandType` 由构造器抛 `IllegalArgumentException`。

同时把类名加入 `seedj-biz-starter` 的 `AutoConfiguration.imports`。

- [ ] **Step 4: 生成 OpenAPI 对照工件并写对齐测试**

```bash
mkdir -p docs/conformance/api
cp /home/wxy/idp/bf/eis/02-operation-contract/command.openapi.yaml docs/conformance/api/
cp /home/wxy/idp/bf/eis/02-operation-contract/query.openapi.yaml docs/conformance/api/
```

`ApiAlignmentTest` 断言：
- YAML 里包含 `POST /commands/{commandType}`、`GET /commands/{id}`、`GET /objects/{objectType}/{id}`、`as_of`、`recorded_as_of`、`min_version`、`Retry-After`；
- `CommandController` / `ObjectQueryController` 的 `@RequestMapping` 路径与方法逐项一致；
- `CommandRequest` / `ObjectQueryResponse` 经 `JsonMapper` 序列化后的键是 `idempotency_key` / `object_type` / `valid_from` 等线格式。

- [ ] **Step 5: 全量验收并记录**

Run: `mvn -o -B verify`
Expected: BUILD SUCCESS（全部模块与 Testcontainers 用例）。

把命令、结果、G2/G3/G4 对照、偏差裁定写入 `docs/conformance/runs/2026-11-05-p2.md`；明确 P3（审计 v1.1）尚未开始。

- [ ] **Step 6: 提交**

```bash
git add seedj-biz-starter/src/main seedj-biz-starter/src/test seedj-it/src/test/java/cn/byteforce/seedj/conformance/ApiAlignmentTest.java docs/conformance
git commit -m "feat: wire command runtime and http api"
git add docs/conformance/runs/2026-11-05-p2.md
git commit -m "docs: record p2 conformance evidence"
```

---

## 自查记录（写完计划后执行）

- **Spec coverage：** P2 退出标准逐条有任务：202/400/409（Task 7）、幂等重放（Task 7）、`GET /commands/{id}`（Task 7）、`Retry-After`（Task 6/8）、OpenAPI 对照（Task 9）；G3 的命令表/存储（Task 2/3/5）、G4 的信封（Task 6）、G2 的时点查询（Task 8）。
- **Type consistency：** `CommandRecord` 的 `payloadFingerprint` 在 `CommandStore`、`CommandRuntime`、PG DDL 三处同名；`CommandReceipt` 的 `code/reason` 与 HTTP 的 `code/reason` 一致；`CommandRuntime.Options` 的 `outboxWriter` 可为 `null`，在计划中明确。
- **Review Focus：** 五个失败模式分别落在 Task 4（并发/拒绝/锁）、Task 8（min_version/半开区间）、Task 6/7（错误形状）。
- **Proportion：** 计划只固定签名、DDL、断言与关键顺序，不展开实现体。
