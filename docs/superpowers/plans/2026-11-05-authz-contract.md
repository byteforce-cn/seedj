# 授权契约实施计划（P4）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 交付 04 授权契约的可执行纵切：双时间轴绑定/代理、`/resolve`、`/check`、SoD、属性快照与声明式条件 fail-closed。

**Architecture:** 纯 Java 语义放 `core/iam/authz`（绑定/代理模型、SoD、条件 DSL、快照状态）；内存实现放 `seedj-infra-local/iam/memory`；PostgreSQL 表与实现放 `seedj-jdbc-postgresql/iam/jdbc`；解析服务、装配与 HTTP 面放 `seedj-iam-starter/iam/authz`。绑定只引用既有 `iam_role`，不替代 `iam_user_role`；角色权限继续从 RBAC 仓储读取。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、AssertJ、Spring MVC / MockMvc、Spring `JdbcTemplate`、Testcontainers（`postgres:17-alpine`）。

**Spec:** `docs/superpowers/specs/2026-11-05-contract-conformance-design.md` §7.7 / §9 P4；规范 `04-authz-contract/`（resolution.api.yaml、attributes.api.yaml、attribute.rules.yaml、sod.rules.yaml、fixtures）。

## Global Constraints

- 产品代码不得出现规范名（不区分大小写），唯一例外：`EIS-{3位}-{3位}`、Schema URI `eis://…`、DDL 域类型 `eis_id` / `eis_version` / `eis_timestamp`。
- `core` 零 Spring、零 seedj 模块依赖；不新增 Maven module。
- 时间 UTC 微秒；半开区间 `[valid_from, valid_to)`；`superseded_at` 为系统时间轴封口。
- `/check` 请求不得携带条件；条件只来自 `IamPermissionCondition` 声明 bean。
- 条件只收窄、不授予；属性缺失/过期/源不可达/不匹配一律拒绝。
- 拒绝原因只含属性路径与状态，不含属性值全文。
- SoD：`prevent` 拒绝；`warn` 进 `/resolve.sod_warnings`；作用域按绑定 `scope` 比较。
- 每个任务结束运行该模块 `mvn -o -B -pl <module> -am test`；提交信息 `feat:` / `test:` / `fix:` / `docs:`。

## Review Focus

- **时点边界**：`as_of == valid_from` 生效；`as_of == valid_to` 失效；`recorded_as_of == superseded_at` 命中封口后的版本。
- **代理期外**：代理过期后不得残留被代理人的角色/权限。
- **SoD 作用域**：不同部门的冲突权限不得误报；同一部门必须报。
- **条件 fail-closed**：快照 stale/missing/source unavailable 时依赖条件的权限必须拒绝；无条件的权限不受影响。
- **`[*]` 语义**：`positions[*].grade` 任一岗位命中；`not_in` 全称语义；路径缺失不得静默放行。
- **无条件权限**：权限未声明条件时 `attribute_state=not_required`，不得因属性源不可达而拒绝。

---

### Task 1: core 授权模型、SoD 与条件求值

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamBinding.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamProxy.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamBindingStore.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/AttributeState.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/AttributeSnapshot.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/AttributeLookup.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamAttributeSource.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamCondition.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamConditionTerm.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamConditionOperator.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamPermissionCondition.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamConditionEvaluator.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodEnforcement.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodScope.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodRule.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodRuleSet.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodFinding.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/SodEvaluator.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamResolution.java`
- Create: `core/src/main/java/cn/byteforce/seedj/iam/authz/IamCheckResult.java`
- Test: `core/src/test/java/cn/byteforce/seedj/iam/authz/SodEvaluatorTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/iam/authz/IamConditionEvaluatorTest.java`

**Interfaces:**
- Produces:
  - `record IamBinding(String id, String principal, String role, Map<String,String> scope, Instant validFrom, Instant validTo, Instant recordedAt, Instant supersededAt)`；`boolean effectiveAt(Instant asOf, Instant recordedAsOf)`。
  - `record IamProxy(String id, String principal, String agent, Map<String,String> scope, Instant validFrom, Instant validTo, Instant recordedAt, Instant supersededAt)`；`boolean effectiveAt(...)`。
  - `interface IamBindingStore { void save(IamBinding); void save(IamProxy); List<IamBinding> effectiveBindings(String principal, Instant asOf, Instant recordedAsOf); List<IamProxy> effectiveProxies(String agent, Instant asOf, Instant recordedAsOf); }`。
  - `enum AttributeState { FRESH, STALE, MISSING, SOURCE_UNAVAILABLE, NOT_REQUIRED }`，`wireName()` 返回小写。
  - `record AttributeSnapshot(String principal, Instant asOf, Instant recordedAt, Instant expiresAt, String source, String sourceVersion, Map<String,Object> snapshot)`；`AttributeState stateAt(Instant requestedAsOf)`。
  - `record AttributeLookup(AttributeState state, AttributeSnapshot snapshot)`。
  - `interface IamAttributeSource { AttributeLookup lookup(String principal, Instant asOf, Instant recordedAsOf); static IamAttributeSource none(); }`。
  - `enum IamConditionOperator { EQ, NEQ, IN, NOT_IN, EXISTS }`。
  - `record IamConditionTerm(String attribute, IamConditionOperator op, List<String> values)`。
  - `record IamCondition(String logic, List<IamConditionTerm> terms)`；`logic` 只接受 `all` / `any`。
  - `record IamPermissionCondition(String permission, IamCondition condition)`。
  - `record ConditionOutcome(boolean matched, String reasonCode, String attribute)`。
  - `IamConditionEvaluator.evaluate(IamCondition, Map<String,Object>) -> ConditionOutcome`。
  - `enum SodEnforcement { PREVENT, WARN }`，`enum SodScope { SAME_DEPARTMENT, SAME_ORGANIZATION, SAME_WAREHOUSE }`（含 `scopeKey()`）。
  - `record SodRule(String id, String name, List<String> conflictingPermissions, SodScope scope, SodEnforcement enforcement)`。
  - `record SodRuleSet(List<SodRule> rules)`；`SodRuleSet.defaults()` 返回 SOD-001..003。
  - `record SodFinding(String ruleId, SodEnforcement enforcement, List<String> permissions)`。
  - `SodEvaluator.findings(List<SodRule>, Map<String,List<Map<String,String>>> permissionScopes) -> List<SodFinding>`。
  - `record IamResolution(List<String> roles, List<String> permissions, List<String> sodWarnings)`。
  - `record IamCheckResult(boolean allowed, String reason, String reasonCode, AttributeState attributeState)`。

- [x] **Step 1: 写失败测试**

`IamConditionEvaluatorTest` 覆盖 `conditions.yaml` 三个条件与 `fixtures-attributes.yaml` 两个快照：

```java
@Test void allInMatchesPrimaryGrade() { /* M2 vs P5 → false, ATTRIBUTE_MISMATCH */ }
@Test void anyPositionsStarMatchesAnyGrade() { /* P6 → true */ }
@Test void notInIsUniversal() { /* M2+P6 vs P6 → false */ }
@Test void missingPathFailsClosedAsMissing() { /* ATTRIBUTE_MISSING */ }
@Test void existsRespectsBooleanValue() { ... }
```

`SodEvaluatorTest`：

```java
@Test void sameDepartmentConflictIsPrevent() { /* create+approve D001 → SOD-001 */ }
@Test void differentDepartmentsDoNotConflict() { /* D001 vs D002 → empty */ }
@Test void missingScopeDoesNotConflict() { ... }
@Test void warnRuleStillProducesFinding() { /* SOD-003 */ }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl core test -Dtest=SodEvaluatorTest,IamConditionEvaluatorTest`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现模型与求值**

- 绑定/代理构造器把时间截断到微秒，校验 `validTo > validFrom`（非空）、`recordedAt` 非空。
- 条件属性路径：点分；段可带 `[*]`；`[*]` 对列表展开为多候选；终值必须是标量。
- `eq` / `in` 存在语义；`neq` / `not_in` 全称语义；`exists` 只接受 `[true]` / `[false]`。
- 路径缺失返回 `ATTRIBUTE_MISSING`；有路径但条件不满足返回 `ATTRIBUTE_MISMATCH`；`any` 的全部失败若含 missing，优先返回 missing。
- SoD 对每条规则的冲突权限取所有授予 scope；按 `scopeKey` 比较，任意一对相等即冲突；同一规则只产出一次。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl core test -Dtest=SodEvaluatorTest,IamConditionEvaluatorTest`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/iam/authz core/src/test/java/cn/byteforce/seedj/iam/authz
git commit -m "feat: add authz model sod and condition evaluator"
```

---

### Task 2: 内存绑定/代理存储与契约

**Files:**
- Create: `seedj-infra-local/src/main/java/cn/byteforce/seedj/iam/memory/InMemoryIamBindingStore.java`
- Create: `seedj-infra-local/src/main/java/cn/byteforce/seedj/iam/memory/InMemoryIamAttributeSource.java`
- Test: `seedj-infra-local/src/test/java/cn/byteforce/seedj/iam/memory/InMemoryIamBindingStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `IamBinding` / `IamProxy` / `IamBindingStore` / `AttributeSnapshot` / `AttributeLookup` / `IamAttributeSource`。
- Produces:
  - `InMemoryIamBindingStore`：线程安全；`effectiveBindings` / `effectiveProxies` 按 `effectiveAt` 过滤；`clear()`。
  - `InMemoryIamAttributeSource`：`save(AttributeSnapshot)`、`markSourceUnavailable(String principal)`、`clear()`；`lookup` 选 `asOf <= 请求 as_of` 的最新快照，过期返回 `STALE`，无快照返回 `MISSING`，标记不可达返回 `SOURCE_UNAVAILABLE`。

- [x] **Step 1: 写失败测试**

```java
@Test void bindingIsEffectiveOnValidFromAndNotOnValidTo() { ... }
@Test void recordedAsOfSelectsBeforeSupersededAt() { ... }
@Test void proxyOnlyEffectiveInsideWindow() { ... }
@Test void attributesSelectLatestNotAfterAsOf() { ... }
@Test void expiredSnapshotIsStale() { ... }
@Test void missingSnapshotIsMissing() { ... }
@Test void unavailableSourceWins() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryIamBindingStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现内存存储**

- 绑定/代理按 `validFrom` 升序返回，保证解析结果稳定。
- 属性快照按 `asOf` 升序保存；查询取不晚于请求时点的最后一条；`expiresAt != null && !requestedAsOf.isBefore(expiresAt)` → stale。
- `sourceUnavailable` 集合优先于快照。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryIamBindingStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add seedj-infra-local/src/main/java/cn/byteforce/seedj/iam/memory seedj-infra-local/src/test/java/cn/byteforce/seedj/iam/memory
git commit -m "feat: add in-memory authz bindings and attributes"
```

---

### Task 3: PostgreSQL 绑定/代理表与存储

**Files:**
- Modify: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/iam/jdbc/IamMigrations.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/iam/jdbc/JdbcIamBindingStore.java`
- Test: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/iam/jdbc/JdbcIamBindingStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `IamBindingStore` 与模型。
- Produces: `iam_binding` / `iam_proxy` 表；`JdbcIamBindingStore` 实现同一 SPI。

- [x] **Step 1: 写失败测试**

```java
@Test void savesAndReadsBindingAtAsOf() { ... }
@Test void validToIsExclusive() { ... }
@Test void recordedAsOfHidesSupersededBinding() { ... }
@Test void proxyWindowIsRespected() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=JdbcIamBindingStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（表不存在）。

- [x] **Step 3: 实现迁移与存储**

`IamMigrations.all()` 追加 `Migration(2, "iam authz bindings", ...)`：

```sql
CREATE TABLE iam_binding (
  id VARCHAR(64) PRIMARY KEY,
  principal VARCHAR(64) NOT NULL,
  role VARCHAR(64) NOT NULL,
  scope JSONB NOT NULL DEFAULT '{}'::jsonb,
  valid_from TIMESTAMPTZ NOT NULL,
  valid_to TIMESTAMPTZ,
  recorded_at TIMESTAMPTZ NOT NULL,
  superseded_at TIMESTAMPTZ
);
CREATE INDEX idx_iam_binding_principal ON iam_binding (principal, valid_from, valid_to);
CREATE TABLE iam_proxy (...agent VARCHAR(64)...);
CREATE INDEX idx_iam_proxy_agent ON iam_proxy (agent, valid_from, valid_to);
```

SQL 过滤：`valid_from <= as_of AND (valid_to IS NULL OR valid_to > as_of)`；`recorded_at <= recorded_as_of`；`(superseded_at IS NULL OR superseded_at > recorded_as_of)`。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=JdbcIamBindingStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/iam/jdbc seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/iam/jdbc
git commit -m "feat: add postgres authz binding store"
```

---

### Task 4: 解析/检查服务与装配

**Files:**
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/IamContractAuthorizationService.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/AuthzProperties.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/IamAuthzConfiguration.java`
- Modify: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/autoconfigure/IamAutoConfiguration.java`
- Test: `seedj-iam-starter/src/test/java/cn/byteforce/seedj/iam/authz/IamContractAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: Task 1–3；`IamAuthorizationRepository`、`IamBindingStore`、`IamAttributeSource`、`List<IamPermissionCondition>`、`SodRuleSet`。
- Produces:
  - `IamContractAuthorizationService.resolve(String principal, Instant asOf, Instant recordedAsOf, Map<String,String> scope)`
  - `IamContractAuthorizationService.check(String principal, String permission, Instant asOf, Instant recordedAsOf, Map<String,Object> providedSnapshot, AttributeState providedState)`
  - `IamContractAuthorizationService.attributes(String principal, Instant asOf, Instant recordedAsOf)`
  - `@ConfigurationProperties("seedj.authz") record AuthzProperties(Boolean enabled, String provider)`（默认 enabled=true、provider 按 IAM store 推断）。

- [x] **Step 1: 写失败测试**

用 `InMemoryIamBindingStore` + `InMemoryIamAuthorizationRepository` 写入 fixtures 的角色/权限/绑定/代理，构造服务并断言：

```java
@Test void historicalCheckBeforeValidFromIsNoPermission() { ... }
@Test void proxyResolvesDelegatorRolesInsideWindow() { ... }
@Test void proxyOutsideWindowIsDenied() { ... }
@Test void preventSodDeniesBothConflictingPermissions() { ... }
@Test void resolveReportsWarnSod() { ... }
@Test void conditionMatchesPrimaryGrade() { ... }
@Test void conditionMismatchIsAttributeMismatch() { ... }
@Test void staleSnapshotFailsClosed() { ... }
@Test void permissionWithoutConditionIgnoresMissingSnapshot() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-iam-starter -am test -Dtest=IamContractAuthorizationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现服务与装配**

- 解析：本人绑定 + 以本人为 agent 的有效代理 → 被代理人绑定；角色经 `findRoleIdsWithAncestors` 展开；权限按绑定 scope 记录。
- `/check` 顺序：权限持有 → prevent SoD → 条件（无条件直接允许）。
- `providedSnapshot` 非空时优先使用；`providedState` 为 stale/missing/source_unavailable 直接拒绝。
- 条件求值前取快照；非 `FRESH` 时映射到 `ATTRIBUTE_STALE` / `ATTRIBUTE_MISSING` / `ATTRIBUTE_SOURCE_UNAVAILABLE`。
- `IamAuthzConfiguration`：`IamBindingStore` provider 选择（`seedj.authz.provider`；缺省按 `seedj.iam.store` 与 `JdbcTemplate` 推断）；`IamAttributeSource` 缺省 `InMemoryIamAttributeSource`；`SodRuleSet` 缺省 `SodRuleSet.defaults()`；`List<IamPermissionCondition>` 收集 bean。
- 把 `IamAuthzConfiguration` 加入 `IamAutoConfiguration` 的 `@Import`。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-iam-starter -am test -Dtest=IamContractAuthorizationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/autoconfigure/IamAutoConfiguration.java seedj-iam-starter/src/test/java/cn/byteforce/seedj/iam/authz
git commit -m "feat: add authz resolution service"
```

---

### Task 5: `/resolve`、`/check` 与属性端点

**Files:**
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/IamAuthorizationController.java`
- Create: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/IamAuthorizationDtos.java`（请求/响应 record）
- Modify: `seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz/IamAuthzConfiguration.java`
- Test: `seedj-iam-starter/src/test/java/cn/byteforce/seedj/iam/authz/IamAuthorizationControllerTest.java`

**Interfaces:**
- Consumes: Task 4 服务。
- Produces: `POST /resolve`、`POST /check`、`GET /principals/{id}/attributes`。

- [x] **Step 1: 写失败测试**

`MockMvcBuilders.standaloneSetup(controller)`：

```java
@Test void resolveReturnsSnakeCaseFields() { ... }
@Test void checkReturnsReasonCodeAndAttributeState() { ... }
@Test void attributesMissingIs409WithReasonCode() { ... }
@Test void attributesUnavailableIs503WithReasonCode() { ... }
@Test void attributesRequireServicePermission() { ... }
```

- [x] **Step 2: 运行确认失败**

Run: `mvn -o -B -pl seedj-iam-starter -am test -Dtest=IamAuthorizationControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，编译错误。

- [x] **Step 3: 实现控制器**

- 请求/响应字段用 `@JsonProperty` 固定 snake_case：`as_of`、`recorded_as_of`、`sod_warnings`、`reason_code`、`attribute_state`、`source_version`。
- `/resolve`、`/check` 返回 200。
- `/check` 返回 `allowed` / `reason` / `reason_code` / `attribute_state`。
- 属性端点：`FRESH` / `STALE` → 200；`MISSING` → 409 `{reason_code:"ATTRIBUTE_MISSING"}`；`SOURCE_UNAVAILABLE` → 503 `{reason_code:"ATTRIBUTE_SOURCE_UNAVAILABLE"}`。
- 属性端点要求 `CallerProvider` 存在且持有 `hr.attributes.read` / `iam.attributes.read`；否则 403（fail-closed）。
- 控制器注册进 `IamAuthzConfiguration` / `IamWebConfiguration`。

- [x] **Step 4: 运行测试**

Run: `mvn -o -B -pl seedj-iam-starter -am test -Dtest=IamAuthorizationControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [x] **Step 5: 提交**

```bash
git add seedj-iam-starter/src/main/java/cn/byteforce/seedj/iam/authz seedj-iam-starter/src/test/java/cn/byteforce/seedj/iam/authz
git commit -m "feat: add authz http api"
```

---

### Task 6: P4 验收与全量验证

**Files:**
- Create: `docs/conformance/runs/2026-11-05-p4.md`
- Modify: `docs/superpowers/plans/2026-11-05-authz-contract.md`

- [x] **Step 1: 全量验证**

Run: `mvn -o -B verify`
Expected: BUILD SUCCESS。

- [x] **Step 2: 写验收记录**

记录：命令、fixtures 对照表（时点/代理/SoD/属性）、偏差裁定（绑定/角色标识沿用既有 IAM 字符串 ID；角色定义不做双时间轴，绑定承担时点语义；属性源可插拔）。

- [x] **Step 3: 提交**

```bash
git add docs/conformance/runs/2026-11-05-p4.md docs/superpowers/plans/2026-11-05-authz-contract.md
git commit -m "docs: record p4 conformance evidence"
```

---

## 自查记录

- **Spec coverage：** 绑定/代理时点（Task 1–3）、`/resolve`（Task 4/5）、`/check` 原因码（Task 4/5）、SoD（Task 1/4）、属性快照与 fail-closed（Task 1/2/4/5）、条件声明化（Task 1/4）。
- **Type consistency：** `AttributeState` 在 core/服务/控制器同名；`SodFinding.ruleId` 与响应 `sod_warnings` 一致；`IamBinding.scope` 在内存/PG/服务三处都是 `Map<String,String>`。
- **Review Focus：** 边界（Task 2/3 测试）、代理期外（Task 4 测试）、SoD 作用域（Task 1 测试）、fail-closed（Task 4 测试）、`[*]`/`not_in`（Task 1 测试）、无条件权限（Task 4 测试）。
