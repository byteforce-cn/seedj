# 活样板与交付实施计划（P6）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付派生字段、Outbox/Inbox 对齐、HR 活样板与合规声明，完成 L1–L4 的可运行闭环。

**Architecture:** 派生字段纯算法放 `core/security`；命令载荷脱敏与读投影剔除在运行时 seam 完成；Inbox 表/存储在 PostgreSQL 模块；HR 样板放 `seedj-server/hr`（TemporalTable + 两个 TemporalCommandAgent + 派生字段），由 `seedj-it` 做端到端验证。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、AssertJ、Spring `JdbcTemplate`、Testcontainers、Jackson 3。

**Spec:** 设计 §7.5 / §7.8 / §9 P6；规范 `extensions/outbox`、`extensions/hr`、`01-data-contract/write-only-fields.rules.yaml`。

## Global Constraints

- 产品代码不得出现规范名（不区分大小写），唯一例外：错误码、Schema URI、DDL 域类型。
- `core` 零 Spring；不新增 Maven module。
- 派生字段：HMAC-SHA256，输入 `scope + 0x00 + 原始值`，输出 64 位小写 hex；scope 前缀 `seedj:`。
- 原始值与派生值不得进入命令记录、事件、读投影、日志或错误响应。
- 密钥环从 `SEEDJ_FIELD_HASH_KEYS` 注入（逗号分隔、首为主）；缺省值只供测试。
- Inbox 幂等键 `(consumer_group, event_id)`；状态 `processing/done/dead`。
- 不改变既有 `OutboxStore` 公共方法签名；`FAILED` 语义迁移为 `DEAD`，运维方法接受两者。
- 每个任务结束运行该模块 `mvn -o -B -pl <module> -am test`；提交信息 `feat:` / `test:` / `docs:`。

## Review Focus

- **派生域分离**：同一原始值在不同 scope 下散列不同；轮换后旧密钥仍能匹配。
- **载荷泄漏**：命令记录、事件、读投影、错误响应均无原始 `id_number` 与派生 `id_number_hash`。
- **Inbox 幂等**：同组同事件重复 claim 返回 false；done 不再处理；dead 需显式 reset；不同组互不影响。
- **Outbox 死信**：不可重试失败进 DEAD，可重试回 PENDING；retry/discard 只对死信生效。
- **HR 时点读**：创建/更新后 `as_of` 查询返回正确版本；更新保留 employee_no/status。
- **空原始值**：空白 `id_number` 必须在派生前拒绝。

---

### Task 1: 派生字段与脱敏 seam

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/DerivedFieldKeyRing.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/security/DerivedField.java`
- Create: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandPayloadRedactor.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/CommandRuntime.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalTable.java`（`seedj-jdbc-postgresql`）
- Modify: `core/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalTables.java`
- Create: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/QueryFieldRedactor.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain/web/ObjectQueryController.java`
- Modify: `seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring/TemporalCommandAutoConfiguration.java`
- Test: `core/src/test/java/cn/byteforce/seedj/core/security/DerivedFieldTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/cqrs/CommandRuntimeRedactionTest.java`

**Interfaces:**
- `DerivedFieldKeyRing(String... keys)` / `fromEnvironment()`；`primary()`、`all()`。
- `DerivedField(String scope, DerivedFieldKeyRing keys)`；`derive(String raw)`、`variants(String raw)`、`matches(String raw, String expected)`。
- `CommandPayloadRedactor.redact(Map<String,Object>)`；`static noop()`。
- `CommandRuntime.Options` 第 10 组件 `CommandPayloadRedactor payloadRedactor`；保留 8/9 参构造器。
- `TemporalTable` 第 4 组件 `Set<String> writeOnlyColumns`；保留 3 参构造器；`TemporalTables.writeOnlyColumns(String)`。
- `QueryFieldRedactor.redact(String objectType, Map<String,Object>)`。

- [x] **Step 1: 写失败测试**

`DerivedFieldTest`：

```java
@Test void derivesStableLowercaseHex() { ... }
@Test void scopeSeparatesSameRawValue() { ... }
@Test void rotationMatchesAllAcceptedKeys() { ... }
@Test void blankRawValueIsRejected() { ... }
```

`CommandRuntimeRedactionTest`：命令记录 payload 不含原始/派生键，但幂等指纹仍基于原载荷（同键同原载荷重放、同键不同原载荷 409）。

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl core test -Dtest=DerivedFieldTest,CommandRuntimeRedactionTest`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现**

- HMAC-SHA256；scope 前缀必须 `seedj:`；密钥非空。
- `fromEnvironment` 空白时回退 `test-only-key` 并 WARN（测试可接受）。
- `CommandRuntime` 只把 `payloadRedactor.redact(submission.payload())` 用于 `CommandRecord`；指纹仍用原载荷。
- `TemporalTable` 增加 `writeOnlyColumns`；`ObjectQueryController` 用 `TemporalTables` 剔除写后返回。
- 自动装配注入 `ObjectProvider<CommandPayloadRedactor>`；默认 noop。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl core test -Dtest=DerivedFieldTest,CommandRuntimeRedactionTest`
Expected: PASS。

Run: `mvn -o -B -pl seedj-biz-starter -am test -Dtest=ObjectQueryControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/core/security core/src/main/java/cn/byteforce/seedj/cqrs core/src/main/java/cn/byteforce/seedj/domain seedj-biz-starter/src/main/java/cn/byteforce/seedj/domain seedj-biz-starter/src/main/java/cn/byteforce/seedj/cqrs/wiring core/src/test/java/cn/byteforce/seedj/core/security core/src/test/java/cn/byteforce/seedj/cqrs
git commit -m "feat: add derived fields and payload redaction"
```

---

### Task 2: Outbox 死信与 Inbox 落库

**Files:**
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox/jdbc/PostgresOutboxMigrations.java`
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox/jdbc/PostgresOutboxStore.java`
- Create: `core/src/main/java/cn/byteforce/seedj/outbox/spi/InboxStatus.java`
- Create: `core/src/main/java/cn/byteforce/seedj/outbox/spi/InboxRecord.java`
- Create: `core/src/main/java/cn/byteforce/seedj/outbox/spi/InboxStore.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox/jdbc/JdbcInboxStore.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/outbox/jdbc/JdbcInboxStoreTest.java`

**Interfaces:**
- `InboxStore.claim(String consumerGroup, UUID eventId) -> boolean`；`markDone`；`markDead`；`resetDead`；`find`。
- `JdbcInboxStore` 使用 `inbox_message` 表。
- Outbox 迁移 V3：加 `aggregate_type`、`dead_at`、`last_error`；`inbox_message` 表。

- [x] **Step 1: 写失败测试**

```java
@Test void duplicateClaimInSameGroupReturnsFalse() { ... }
@Test void differentGroupsClaimIndependently() { ... }
@Test void doneCannotBeClaimedAgain() { ... }
@Test void deadNeedsExplicitReset() { ... }
@Test void outboxNonRetryableFailureBecomesDead() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=JdbcInboxStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（表/类型不存在）。

- [x] **Step 3: 实现**

- Inbox 表按规范 `outbox.ddl` 最小列；`claim` 用 `INSERT ... ON CONFLICT DO NOTHING`，仅 `dead` 不计为 claim 成功。
- Outbox `markFailed(false)` → `DEAD` + `dead_at` + `last_error`；claim 顺序屏障包含 `DEAD`；`retry`/`discard` 接受 `FAILED`/`DEAD`。
- 更新 `OutboxStore` javadoc 与契约测试名（如需要）。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test`
Expected: PASS（含既有 outbox 契约）。

- [x] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/outbox seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/outbox seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/outbox
git commit -m "feat: align outbox dead letters and inbox"
```

---

### Task 3: HR 活样板

**Files:**
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeConfiguration.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeCreateAgent.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeUpdateAgent.java`
- Create: `seedj-server/hr/src/main/java/cn/byteforce/seedj/server/hr/employee/HrEmployeeFields.java`
- Test: `seedj-it/src/test/java/cn/byteforce/seedj/it/HrEmployeeSampleTest.java`
- Modify: `seedj-it/pom.xml`（仅当需要）

**Interfaces:**
- `TemporalTable("employee", "hr_employee", columns, Set.of("id_number_hash"))`。
- `DerivedField` scope `seedj:hr:employee:id_number_hash:v1`。
- 命令：`hr.employee.create`、`hr.employee.update`；事件：`hr.employee.created` / `hr.employee.updated`。
- `HrEmployeeFields` 负责生成 `employee_no`（`EMP-%06d`）、合并更新字段、剔除原始/派生字段。

- [x] **Step 1: 写失败测试**

`HrEmployeeSampleTest` 用内存 runtime 执行：

```java
@Test void createDerivesHashAndHidesItFromCommandRecord() { ... }
@Test void readProjectionOmitsWriteOnlyField() { ... }
@Test void updateKeepsEmployeeNoAndStatus() { ... }
@Test void asOfReadReturnsVersion() { ... }
@Test void blankIdNumberIsRejectedBeforeDerivation() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-it -am test -Dtest=HrEmployeeSampleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现**

- 两个 agent 共享 `HrEmployeeFields`；创建目标 ID 在 `targetId`/`append` 间用 `ThreadLocal` 传递。
- `validate` 拒绝空白 `id_number`（若提供）。
- 事件 payload 只含安全字段；命令记录由 `CommandPayloadRedactor` 剔除 `id_number`/`id_number_hash`。
- `seedj-it` 用 `TemporalTables` + `ObjectQueryController` 验证读投影剔除。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-it -am test -Dtest=HrEmployeeSampleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add seedj-server/hr/src/main/java seedj-it/src/test/java/cn/byteforce/seedj/it/HrEmployeeSampleTest.java seedj-it/pom.xml
git commit -m "feat: add hr employee live sample"
```

---

### Task 4: 合规声明与 P6 记录

**Files:**
- Create: `README.md`
- Create: `docs/conformance/runs/2026-11-05-p6.md`
- Modify: `docs/superpowers/plans/2026-11-05-live-sample-and-delivery.md`

- [x] **Step 1: 写 README**

包含：项目定位、L1–L4 能力、如何运行（`mvn verify`、`-Pconformance`）、合规边界（规范名清理、审计 v1.1、时点授权、演进门禁）、已知偏差（标识列文本、角色无独立时点、HR 样板只覆盖 create/update）。

- [x] **Step 2: 全量验证**

Run: `mvn -o -B verify`
Expected: BUILD SUCCESS。

Run: `mvn -o -B -Pconformance -DskipTests verify`
Expected: BUILD SUCCESS。

- [x] **Step 3: 写验收记录并提交**

```bash
git add README.md docs/conformance/runs/2026-11-05-p6.md docs/superpowers/plans/2026-11-05-live-sample-and-delivery.md
git commit -m "docs: record p6 conformance evidence"
```

---

## 自查记录

- **Spec coverage：** 派生字段（Task 1）、Outbox/Inbox（Task 2）、HR 样板（Task 3）、声明与验证（Task 4）。
- **Type consistency：** `DerivedField` 在 core/sample 同名；`TemporalTable.writeOnlyColumns` 在读投影与 sample 注册一致；`InboxStore.claim` 返回 boolean。
- **Review Focus：** 域分离/轮换（Task 1）、泄漏（Task 1/3）、Inbox（Task 2）、死信（Task 2）、时点读（Task 3）、空值（Task 3）。
